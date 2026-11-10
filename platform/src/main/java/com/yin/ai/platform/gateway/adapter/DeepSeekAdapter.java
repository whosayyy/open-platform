package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.exception.GatewayException;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.stream.FinishPacket;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.structured.StructuredToolGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * DeepSeek 模型适配器（兼容 OpenAI API）。
 * <p>
 * 使用 Spring AI OpenAI Starter + MessageConverter/OptionResolver 分层，
 * 不再直接持有 Spring AI Message 依赖。
 */
@Slf4j
@Component
public class DeepSeekAdapter implements ModelAdapter {

    private final ChatClient chatClient;
    private final MessageConverter<Message> messageConverter;
    private final OptionResolver optionResolver;
    private final FunctionRegistry functionRegistry;
    private final SinkStreamProducer streamProducer;

    public DeepSeekAdapter(ChatClient.Builder chatClientBuilder,
                           MessageConverter<Message> messageConverter,
                           OptionResolver optionResolver,
                           FunctionRegistry functionRegistry,
                           @Autowired(required = false) SinkStreamProducer streamProducer) {
        this.chatClient = chatClientBuilder.build();
        this.messageConverter = messageConverter;
        this.optionResolver = optionResolver;
        this.functionRegistry = functionRegistry;
        this.streamProducer = streamProducer;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of("deepseek-v4-flash", "deepseek-v4-pro",
                "deepseek-chat", "deepseek-reasoner");
    }

    @Override
    public GatewayResponse call(GatewayRequest request) {
        long start = System.currentTimeMillis();
        try {
            ChatClient.ChatClientRequestSpec spec = buildSpec(request);
            ChatResponse chatResponse = spec.call().chatResponse();
            return buildResponse(request, chatResponse, start);
        } catch (Exception e) {
            GatewayRequest fallback = buildToolChoiceFallbackRequest(request, e);
            if (fallback != null) {
                log.warn("[ADAPTER] forced tool_choice rejected by thinking-mode model, retrying without tool_choice: {}",
                        e.getMessage());
                return call(fallback);
            }
            throw translateError("call", request, e);
        }
    }

    @Override
    public Flux<StreamPacket> stream(GatewayRequest request) {
        return streamInternal(request, false);
    }

    private Flux<StreamPacket> streamInternal(GatewayRequest request, boolean fallbackAttempted) {
        long start = System.currentTimeMillis();
        try {
            ChatClient.ChatClientRequestSpec spec = buildSpec(request);
            AtomicInteger seq = new AtomicInteger(0);
            AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();

            return spec.stream().chatResponse()
                    .flatMapIterable(response -> {
                        lastResponse.set(response);
                        java.util.List<StreamPacket> packets = new java.util.ArrayList<>(2);

                        // reasoning_content is intentionally not converted into
                        // a client packet. Only lifecycle, effective mode and
                        // aggregate usage may leave the runtime boundary.

                        // 正常文本内容
                        String content = extractContent(response);
                        if (content != null && !content.isEmpty()) {
                            packets.add(buildDataPacket(content, request, seq.getAndIncrement()));
                        }

                        // 如果两者都为空（如首个空 chunk），发一个空 text 包保持流活跃
                        if (packets.isEmpty()) {
                            packets.add(buildDataPacket("", request, seq.getAndIncrement()));
                        }
                        return packets;
                    })
                    .concatWith(Flux.defer(() -> {
                        long elapsed = System.currentTimeMillis() - start;
                        FinishPacket finish = buildFinishPacket(request, lastResponse.get(), elapsed);
                        StreamPacket packet = new StreamPacket();
                        packet.setType("finish");
                        packet.setContent(finish);
                        packet.setTaskId(request.getTraceId());
                        packet.setSeq(seq.getAndIncrement());
                        packet.setTs(System.currentTimeMillis());
                        packet.setTraceId(request.getTraceId());
                        return Flux.just(packet);
                    }))
                    .onErrorResume(e -> streamFallbackOrError(request, fallbackAttempted, e));
        } catch (Exception e) {
            return streamFallbackOrError(request, fallbackAttempted, e);
        }
    }

    /** 思考模式降级：构造 forceToolChoice=false 的请求副本（仍注册 capture-only 工具、禁用内部执行）。 */
    private GatewayRequest buildToolChoiceFallbackRequest(GatewayRequest request, Throwable error) {
        if (request == null || request.getOptions() == null) {
            return null;
        }
        GatewayOptions options = request.getOptions();
        if (!options.isForceToolChoice()
                || options.getForcedToolName() == null || options.getForcedToolName().isBlank()
                || !StructuredToolGenerator.isThinkingModeToolChoiceRejection(error)) {
            return null;
        }
        GatewayOptions fallbackOptions = options.copy();
        fallbackOptions.setForceToolChoice(false);

        GatewayRequest fallback = new GatewayRequest();
        fallback.setMessages(request.getMessages());
        fallback.setSystemPrompt(request.getSystemPrompt());
        fallback.setFunctionNames(request.getFunctionNames());
        fallback.setOptions(fallbackOptions);
        fallback.setTraceId(request.getTraceId());
        fallback.setCaller(request.getCaller());
        fallback.setPromptTemplate(request.getPromptTemplate());
        fallback.setPromptVariables(request.getPromptVariables());
        fallback.setTenantId(request.getTenantId());
        return fallback;
    }

    private Flux<StreamPacket> streamFallbackOrError(GatewayRequest request, boolean fallbackAttempted,
                                                     Throwable error) {
        GatewayRequest fallback = buildToolChoiceFallbackRequest(request, error);
        if (fallback != null && !fallbackAttempted) {
            log.warn("[ADAPTER] forced tool_choice rejected by thinking-mode model, retrying without tool_choice: {}",
                    error.getMessage());
            return streamInternal(fallback, true);
        }
        return Flux.error(translateError("stream", request, error));
    }

    private ChatClient.ChatClientRequestSpec buildSpec(GatewayRequest request) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();

        // System prompt: 单独字段优先级高于 messages 中的 system 角色
        if (request.getSystemPrompt() != null) {
            spec = spec.system(request.getSystemPrompt());
        }

        // 转换消息
        List<Message> messages = request.getMessages().stream()
                .map(messageConverter::convert)
                .collect(Collectors.toList());
        if (!messages.isEmpty()) {
            spec = spec.messages(messages);
        }

        // 选项
        ChatOptions options = optionResolver.resolve(request.getOptions());
        if (options != null) {
            spec = spec.options(options);
        }

        // 强制工具（structured output）：注册 capture-only 工具，禁用内部执行，参数由响应暴露
        GatewayOptions gwOptions = request.getOptions();
        if (gwOptions != null && gwOptions.getForcedToolName() != null
                && !gwOptions.getForcedToolName().isBlank()
                && gwOptions.getOutputSchema() != null) {
            ToolCallback capture = StructuredToolGenerator.captureTool(
                    gwOptions.getForcedToolName(), CAPTURE_TOOL_DESCRIPTION, gwOptions.getOutputSchema());
            spec = spec.toolCallbacks(capture);
        }

        // 工具（Function Calling）：按 functionNames 从 FunctionRegistry 解析 ToolCallback
        if (gwOptions != null && gwOptions.isEnableFunctionCalling()
                && request.getFunctionNames() != null && !request.getFunctionNames().isEmpty()) {
            List<String> names = request.getFunctionNames().stream().toList();
            List<ToolCallback> toolCallbacks = functionRegistry.getToolCallbacks(names);
            if (!toolCallbacks.isEmpty()) {
                SecurityContext securityContext = SecurityContextHolder.getContext();
                String taskId = request.getTraceId();
                ToolCallback[] wrapped = toolCallbacks.stream()
                        .map(tc -> wrapWithSecurityContext(tc, securityContext))
                        .map(tc -> wrapWithSinkEmission(tc, taskId))
                        .toArray(ToolCallback[]::new);
                spec = spec.toolCallbacks(wrapped);
            }
        }

        // CacheControlAdvisor：检测 GatewayMessage.cacheControl 标记
        Map<Integer, Boolean> cacheableByIndex = new HashMap<>();
        List<GatewayMessage> gwMessages = request.getMessages();
        if (gwMessages != null) {
            for (int i = 0; i < gwMessages.size(); i++) {
                if (gwMessages.get(i).isCacheControl()) {
                    cacheableByIndex.put(i, true);
                }
            }
        }
        if (!cacheableByIndex.isEmpty()) {
            spec = spec.advisors(new CacheControlAdvisor(cacheableByIndex));
        }

        return spec;
    }

    private static ToolCallback wrapWithSecurityContext(ToolCallback delegate, SecurityContext securityContext) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                SecurityContext previous = SecurityContextHolder.getContext();
                try {
                    if (securityContext != null && securityContext.getAuthentication() != null) {
                        SecurityContextHolder.setContext(securityContext);
                    }
                    return delegate.call(toolInput);
                } finally {
                    SecurityContextHolder.setContext(previous);
                }
            }
        };
    }

    private ToolCallback wrapWithSinkEmission(ToolCallback delegate, String taskId) {
        if (streamProducer == null) return delegate;
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                String toolName = delegate.getToolDefinition().name();
                streamProducer.emitToolCall(taskId, "invoking", toolName, toolInput);
                try {
                    String result = delegate.call(toolInput);
                    streamProducer.emitToolCall(taskId, "result", toolName, result);
                    return result;
                } catch (RuntimeException e) {
                    streamProducer.emitToolCallError(taskId, toolName, e.getMessage());
                    throw e;
                }
            }
        };
    }

    private GatewayResponse buildResponse(GatewayRequest request, ChatResponse chatResponse, long start) {
        GatewayResponse response = new GatewayResponse();
        response.setTraceId(request.getTraceId());
        long elapsed = System.currentTimeMillis() - start;
        response.setLatencyMs(elapsed);
        // 阻塞调用：首 Token 到达时间 ≈ 总延迟
        response.setTtftMs(elapsed);

        if (chatResponse == null) {
            response.setContent("");
            return response;
        }

        // 提取文本
        if (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
            response.setContent(chatResponse.getResult().getOutput().getText());
        }

        // 强制工具参数：capture-only 工具的内部执行被禁用，参数原样暴露在响应中
        GatewayOptions gwOptions = request.getOptions();
        if (gwOptions != null && gwOptions.getForcedToolName() != null
                && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null
                && chatResponse.getResult().getOutput().hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : chatResponse.getResult().getOutput().getToolCalls()) {
                if (toolCall != null && gwOptions.getForcedToolName().equals(toolCall.name())
                        && toolCall.arguments() != null && !toolCall.arguments().isBlank()) {
                    response.setToolCallArguments(toolCall.arguments());
                    break;
                }
            }
        }

        // Token 用量
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            TokenUsage tokenUsage = new TokenUsage(
                    usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                    usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                    usage.getTotalTokens() != null ? usage.getTotalTokens() : 0
            );
            Integer cachedTokens = extractCachedPromptTokens(usage);
            if (cachedTokens != null && cachedTokens > 0) {
                tokenUsage.setCachedPromptTokens(cachedTokens);
                tokenUsage.setCacheHit(true);
            }
            response.setTokenUsage(tokenUsage);
        }

        // 模型 & 结束原因
        if (chatResponse.getMetadata() != null) {
            response.setModel(chatResponse.getMetadata().getModel());
        }
        if (chatResponse.getResult() != null && chatResponse.getResult().getMetadata() != null) {
            response.setFinishReason(chatResponse.getResult().getMetadata().getFinishReason());
        }

        return response;
    }

    private static String extractContent(ChatResponse response) {
        if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null) {
            String text = response.getResult().getOutput().getText();
            return text != null ? text : "";
        }
        return "";
    }

    private static StreamPacket buildDataPacket(String content, GatewayRequest request, int seq) {
        StreamPacket packet = new StreamPacket();
        packet.setType("text");
        packet.setContent(content);
        packet.setTaskId(request.getTraceId());
        packet.setSeq(seq);
        packet.setTs(System.currentTimeMillis());
        packet.setTraceId(request.getTraceId());
        return packet;
    }

    private FinishPacket buildFinishPacket(GatewayRequest request, ChatResponse lastResponse, long elapsed) {
        TokenUsage usage = null;
        String model = null;
        String finishReason = null;

        if (lastResponse != null) {
            if (lastResponse.getMetadata() != null) {
                model = lastResponse.getMetadata().getModel();
                if (lastResponse.getMetadata().getUsage() != null) {
                    var u = lastResponse.getMetadata().getUsage();
                    usage = new TokenUsage(
                            u.getPromptTokens() != null ? u.getPromptTokens() : 0,
                            u.getCompletionTokens() != null ? u.getCompletionTokens() : 0,
                            u.getTotalTokens() != null ? u.getTotalTokens() : 0
                    );
                    Integer cached = extractCachedPromptTokens(u);
                    if (cached != null && cached > 0) {
                        usage.setCachedPromptTokens(cached);
                        usage.setCacheHit(true);
                    }
                }
            }
            if (lastResponse.getResult() != null && lastResponse.getResult().getMetadata() != null) {
                finishReason = lastResponse.getResult().getMetadata().getFinishReason();
            }
        }

        return new FinishPacket(usage, model, finishReason, elapsed, request.getTraceId());
    }

    private static Integer extractCachedPromptTokens(org.springframework.ai.chat.metadata.Usage usage) {
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof OpenAiApi.Usage openAiUsage) {
            var details = openAiUsage.promptTokensDetails();
            if (details != null && details.cachedTokens() != null && details.cachedTokens() > 0) {
                return details.cachedTokens();
            }
        }
        return null;
    }

    private static GatewayException translateError(String phase, GatewayRequest request, Throwable e) {
        if (e instanceof GatewayException) {
            return (GatewayException) e;
        }
        return new GatewayException(GatewayException.Code.PROVIDER_ERROR, "deepseek",
                "[" + phase + "] " + e.getMessage(), e);
    }

    private static final String CAPTURE_TOOL_DESCRIPTION = "提交结构化结果。当需要结构化输出时必须调用本工具，"
            + "把结果作为工具参数返回，不要输出任何文字。";
}
