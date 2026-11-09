package com.yin.ai.platform.memory.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Two-second, fail-closed LLM fallback for genuinely ambiguous candidates. */
public class LlmMemoryCandidateClassifier implements MemoryCandidateClassifier {
    private final ModelAdapter modelAdapter;
    private final ObjectMapper objectMapper;

    public LlmMemoryCandidateClassifier(ModelAdapter modelAdapter, ObjectMapper objectMapper) {
        this.modelAdapter = modelAdapter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MemoryWriteDecision> classify(MemoryCandidate candidate,
                                                   MemoryAccessContext context) {
        GatewayRequest request = new GatewayRequest();
        request.setMessages(List.of(
                GatewayMessage.system("Classify memory candidates. Return JSON only: "
                        + "{remember:boolean,type:EPISODIC|SEMANTIC|PROCEDURAL,"
                        + "scope:SESSION|TASK|USER,importance:0..1,confidence:0..1,reason:string}. "
                        + "Reject acknowledgements and runtime state. Never choose GLOBAL or TENANT."),
                GatewayMessage.user(candidate.content())));
        GatewayOptions options = new GatewayOptions();
        options.setTemperature(0.0);
        options.setMaxTokens(160);
        options.setTimeout(Duration.ofSeconds(2));
        options.setJsonResponse(true);
        request.setOptions(options);
        request.setCaller("MemoryWritePolicy");
        request.setTraceId(context.traceId() != null ? context.traceId()
                : "mem-classify-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            String content = modelAdapter.call(request).getContent();
            JsonNode json = objectMapper.readTree(content);
            if (!json.path("remember").asBoolean(false)) {
                return Optional.of(MemoryWriteDecision.reject(
                        json.path("reason").asText("LLM classifier rejected candidate")));
            }
            MemoryType type = MemoryType.valueOf(json.path("type").asText("EPISODIC")
                    .toUpperCase(Locale.ROOT));
            if (type == MemoryType.WORKING || type == MemoryType.SESSION) type = MemoryType.EPISODIC;
            MemoryScope scope = MemoryScope.valueOf(json.path("scope").asText("SESSION")
                    .toUpperCase(Locale.ROOT));
            if (scope == MemoryScope.GLOBAL || scope == MemoryScope.TENANT) return Optional.empty();
            String scopeId = scopeId(scope, context);
            if (scopeId == null) return Optional.empty();
            Duration retention = type == MemoryType.EPISODIC
                    ? (scope == MemoryScope.SESSION ? Duration.ofMinutes(30) : Duration.ofDays(7))
                    : Duration.ofDays(365);
            return Optional.of(new MemoryWriteDecision(true, type, scope, scopeId,
                    json.path("importance").asDouble(0.5),
                    json.path("confidence").asDouble(0.6), retention,
                    json.path("reason").asText("LLM fallback classification")));
        } catch (RuntimeException exception) {
            return Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String scopeId(MemoryScope scope, MemoryAccessContext context) {
        return switch (scope) {
            case USER -> context.userId();
            case SESSION -> context.sessionId();
            case TASK -> context.taskId();
            case WORKFLOW -> context.workflowId();
            case AGENT -> context.agentId();
            case STEP -> context.stepId();
            case TENANT -> context.tenantId();
            case GLOBAL -> null;
        };
    }
}
