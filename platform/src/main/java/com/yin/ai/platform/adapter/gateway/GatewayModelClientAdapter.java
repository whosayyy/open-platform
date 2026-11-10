package com.yin.ai.platform.adapter.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEvent;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelToolCall;
import com.yin.ai.platform.core.model.ModelUsage;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.StreamPacket;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts the existing unified gateway to the framework-neutral model SPI.
 * Reactor and gateway types deliberately stop at this adapter boundary.
 */
public final class GatewayModelClientAdapter implements ModelClient {

    private static final String DEFAULT_CALLER = "core:model";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UnifiedChatClient gateway;

    public GatewayModelClientAdapter(UnifiedChatClient gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public ModelResponse execute(ModelRequest request, ExecutionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        context.cancellationToken().throwIfCancellationRequested();
        return toCoreResponse(gateway.chat(toGatewayRequest(request, context)));
    }

    @Override
    public ModelCall submit(ModelRequest request, ExecutionContext context,
                            ModelEventListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        ModelEventListener eventListener = listener == null ? ModelEventListener.NOOP : listener;
        context.cancellationToken().throwIfCancellationRequested();

        CompletableFuture<ModelResponse> completion = new CompletableFuture<>();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        StringBuilder content = new StringBuilder();
        AtomicReference<String> completedContent = new AtomicReference<>();
        AtomicReference<StreamPacket> lastPacket = new AtomicReference<>();

        Disposable disposable = gateway.chatStream(toGatewayRequest(request, context))
                .doOnNext(ignored -> context.cancellationToken().throwIfCancellationRequested())
                .subscribe(packet -> {
                    lastPacket.set(packet);
                    String type = eventType(packet);
                    String delta = packetContent(packet);
                    notifyListener(eventListener, new ModelEvent(type, delta, packetMetadata(packet)));
                    if ("text".equals(packet.getType())) {
                        content.append(delta);
                    } else if ("complete".equals(packet.getType())) {
                        completedContent.set(delta);
                    } else if ("error".equals(packet.getType())) {
                        throw new IllegalStateException(delta.isBlank()
                                ? "AI Gateway stream failed" : delta);
                    }
                }, completion::completeExceptionally, () -> {
                    String finalContent = completedContent.get();
                    if (finalContent == null || finalContent.isBlank()) {
                        finalContent = content.toString();
                    }
                    completion.complete(new ModelResponse(finalContent, "stop",
                            ModelUsage.empty(), streamMetadata(lastPacket.get())));
                });
        subscription.set(disposable);

        return new ModelCall() {
            @Override
            public java.util.concurrent.CompletionStage<ModelResponse> completion() {
                return completion;
            }

            @Override
            public boolean cancel() {
                boolean cancelled = completion.completeExceptionally(
                        new CancellationException("Model call was cancelled"));
                Disposable current = subscription.get();
                if (current != null) {
                    current.dispose();
                }
                return cancelled;
            }
        };
    }

    private static GatewayRequest toGatewayRequest(ModelRequest request,
                                                   ExecutionContext context) {
        GatewayOptions options = new GatewayOptions();
        options.setModel(request.options().model());
        options.setTemperature(request.options().temperature());
        options.setMaxTokens(request.options().maxOutputTokens());

        List<GatewayMessage> messages = new ArrayList<>();
        for (ModelMessage message : request.messages()) {
            GatewayMessage gatewayMessage = new GatewayMessage();
            gatewayMessage.setRole(switch (message.role()) {
                case SYSTEM -> GatewayMessage.Role.system;
                case USER -> GatewayMessage.Role.user;
                case ASSISTANT -> GatewayMessage.Role.assistant;
                case TOOL -> GatewayMessage.Role.tool;
            });
            gatewayMessage.setContent(message.content());
            gatewayMessage.setName(message.name());
            gatewayMessage.setToolCallId(textValue(message.metadata().get("toolCallId")));
            messages.add(gatewayMessage);
        }

        GatewayRequest gatewayRequest = new GatewayRequest(messages, options);
        gatewayRequest.setFunctionNames(new LinkedHashSet<>(request.allowedTools()));
        gatewayRequest.setTraceId(context.traceId());
        gatewayRequest.setCaller(hasText(context.caller()) ? context.caller() : DEFAULT_CALLER);
        gatewayRequest.setTenantId(context.tenantId());
        gatewayRequest.setUserId(context.userId());
        gatewayRequest.setSessionId(context.conversationId());
        gatewayRequest.setRoles(stringSet(context.attributes().get("roles")));
        gatewayRequest.setPermissions(stringSet(context.attributes().get("permissions")));
        gatewayRequest.setPromptTemplate(textValue(request.metadata().get("promptTemplate")));
        gatewayRequest.setPromptVariables(objectMap(request.metadata().get("promptVariables")));
        gatewayRequest.setPromptVersion(textValue(request.metadata().get("promptVersion")));
        gatewayRequest.setExperimentId(textValue(request.metadata().get("experimentId")));
        gatewayRequest.setVariantId(textValue(request.metadata().get("variantId")));
        return gatewayRequest;
    }

    private static ModelResponse toCoreResponse(GatewayResponse response) {
        if (response == null) {
            throw new IllegalStateException("AI Gateway returned no response");
        }
        Map<String, Object> metadata = mutableNonNullMap(response.getMetadata());
        putIfPresent(metadata, "model", response.getModel());
        putIfPresent(metadata, "traceId", response.getTraceId());
        metadata.put("latencyMs", response.getLatencyMs());
        metadata.put("ttftMs", response.getTtftMs());
        metadata.put("fallback", response.isFallback());
        metadata.put("retryCount", response.getRetryCount());
        metadata.put("cached", response.isCached());
        List<ModelToolCall> toolCalls = toCoreToolCalls(response.getToolCalls());
        if (!toolCalls.isEmpty()) metadata.put("toolCalls", toolCalls);
        return new ModelResponse(response.getContent(), response.getFinishReason(),
                toCoreUsage(response.getTokenUsage()), metadata, toolCalls);
    }

    private static List<ModelToolCall> toCoreToolCalls(
            List<GatewayResponse.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();
        return toolCalls.stream()
                .filter(Objects::nonNull)
                .filter(call -> hasText(call.getName()))
                .map(call -> new ModelToolCall(call.getId(), call.getName(),
                        parseArguments(call.getArguments())))
                .toList();
    }

    private static Map<String, Object> parseArguments(String arguments) {
        if (!hasText(arguments)) return Map.of();
        try {
            return JSON.readValue(arguments, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of("_raw", arguments);
        }
    }

    private static ModelUsage toCoreUsage(TokenUsage usage) {
        if (usage == null) {
            return ModelUsage.empty();
        }
        return new ModelUsage(number(usage.getPromptTokens()),
                number(usage.getCompletionTokens()), number(usage.getTotalTokens()));
    }

    private static Map<String, Object> packetMetadata(StreamPacket packet) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sequence", packet.getSequence() > 0
                ? packet.getSequence() : packet.getSeq());
        putIfPresent(metadata, "timestamp", packet.getTimestamp());
        putIfPresent(metadata, "eventId", packet.getEventId());
        putIfPresent(metadata, "eventType", packet.getEventType());
        putIfPresent(metadata, "taskId", packet.getTaskId());
        putIfPresent(metadata, "sessionId", packet.getSessionId());
        putIfPresent(metadata, "stepId", packet.getStepId());
        putIfPresent(metadata, "traceId", packet.getTraceId());
        if (packet.getPayload() != null && !packet.getPayload().isEmpty()) {
            metadata.put("payload", Map.copyOf(packet.getPayload()));
        }
        return metadata;
    }

    private static Map<String, Object> streamMetadata(StreamPacket packet) {
        if (packet == null) {
            return Map.of("streaming", true);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(packetMetadata(packet));
        metadata.put("streaming", true);
        return metadata;
    }

    private static String eventType(StreamPacket packet) {
        return hasText(packet.getEventType()) ? packet.getEventType()
                : hasText(packet.getType()) ? packet.getType() : "unknown";
    }

    private static String packetContent(StreamPacket packet) {
        return packet.getContent() == null ? "" : String.valueOf(packet.getContent());
    }

    private static void notifyListener(ModelEventListener listener, ModelEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException ignored) {
            // Observers must not be able to abort an in-flight model call.
        }
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            String text = textValue(item);
            if (hasText(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private static Map<String, Object> mutableNonNullMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(key, value);
                }
            });
        }
        return result;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || hasText(text))) {
            target.put(key, value);
        }
    }

    private static long number(Integer value) {
        return value == null ? 0 : value.longValue();
    }

    private static String textValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
