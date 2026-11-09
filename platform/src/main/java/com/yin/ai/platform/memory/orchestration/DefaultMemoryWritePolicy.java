package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic-first long-term write policy. */
public class DefaultMemoryWritePolicy implements MemoryWritePolicy {
    private static final Pattern TRIVIAL = Pattern.compile(
            "^(谢谢|感谢|好的|好|嗯|继续|收到|知道了|ok|okay|thanks|thank you)[。！？!?]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEMANTIC_SIGNAL = Pattern.compile(
            "(以后|默认|记住|偏好|喜欢|不喜欢|必须|不要|请始终|prefer|remember|default|must|always)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> RUNTIME_TYPES = Set.of(
            "PlanNode", "VariableScope", "IntermediateResult", "ResumePoint", "Checkpoint");
    private final MemoryCandidateClassifier classifier;

    public DefaultMemoryWritePolicy() {
        this(null);
    }

    public DefaultMemoryWritePolicy(MemoryCandidateClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public MemoryWriteDecision evaluate(MemoryCandidate candidate,
                                        MemoryAccessContext context) {
        if (candidate == null || candidate.content().isBlank()) {
            return MemoryWriteDecision.reject("blank memory candidate");
        }
        if (context == null) return MemoryWriteDecision.reject("missing access context");
        if (TRIVIAL.matcher(candidate.content().trim()).matches()) {
            return MemoryWriteDecision.reject("transient acknowledgement");
        }
        Object runtimeType = candidate.metadata().get("runtimeType");
        if (runtimeType != null && RUNTIME_TYPES.contains(runtimeType.toString())) {
            return MemoryWriteDecision.reject("agent runtime state is not long-term memory");
        }

        boolean semanticSignal = SEMANTIC_SIGNAL.matcher(candidate.content()).find();
        MemoryType type = normalize(candidate.suggestedType());
        if (type == null) {
            if (semanticSignal || candidate.subject() != null) {
                type = MemoryType.SEMANTIC;
            } else if (Boolean.TRUE.equals(candidate.metadata().get("rawEpisodeStored"))) {
                type = MemoryType.EPISODIC;
            } else if (classifier != null) {
                MemoryWriteDecision classified = classifier.classify(candidate, context).orElse(null);
                if (classified != null) return classified;
                type = MemoryType.EPISODIC;
            } else {
                type = MemoryType.EPISODIC;
            }
        }
        ScopeTarget target = resolveScope(candidate, context, type);
        if (target.scopeId() == null) {
            return MemoryWriteDecision.reject("required scope identity is missing");
        }
        boolean explicit = semanticSignal;
        double importance = type == MemoryType.SEMANTIC ? (explicit ? 0.85 : 0.65) : 0.45;
        double confidence = sourceConfidence(candidate.sourceType());
        Duration retention = switch (type) {
            case EPISODIC -> target.scope() == MemoryScope.SESSION
                    ? Duration.ofMinutes(30) : Duration.ofDays(7);
            case SEMANTIC, PROCEDURAL -> Duration.ofDays(365);
            case WORKING -> Duration.ofHours(1);
            case SESSION -> Duration.ofMinutes(30);
        };
        return new MemoryWriteDecision(true, type, target.scope(), target.scopeId(),
                importance, confidence, retention, "deterministic policy");
    }

    private MemoryType normalize(MemoryType type) {
        return type == MemoryType.SESSION ? MemoryType.EPISODIC : type;
    }

    private ScopeTarget resolveScope(MemoryCandidate candidate,
                                     MemoryAccessContext context, MemoryType type) {
        MemoryScope preferred = candidate.suggestedScope();
        if (preferred != null && preferred != MemoryScope.GLOBAL) {
            return new ScopeTarget(preferred, scopeId(preferred, context));
        }
        if (type == MemoryType.SEMANTIC) {
            return new ScopeTarget(MemoryScope.USER, context.userId());
        }
        String source = candidate.sourceType() != null
                ? candidate.sourceType().toLowerCase(Locale.ROOT) : "";
        if (source.contains("session") || source.contains("conversation")) {
            return new ScopeTarget(MemoryScope.SESSION, context.sessionId());
        }
        if (source.contains("task")) {
            return new ScopeTarget(MemoryScope.TASK, context.taskId());
        }
        return context.taskId() != null
                ? new ScopeTarget(MemoryScope.TASK, context.taskId())
                : new ScopeTarget(MemoryScope.SESSION, context.sessionId());
    }

    private String scopeId(MemoryScope scope, MemoryAccessContext context) {
        return switch (scope) {
            case TENANT -> context.tenantId();
            case USER -> context.userId();
            case SESSION -> context.sessionId();
            case TASK -> context.taskId();
            case WORKFLOW -> context.workflowId();
            case AGENT -> context.agentId();
            case STEP -> context.stepId();
            case GLOBAL -> null;
        };
    }

    private double sourceConfidence(String sourceType) {
        if (sourceType == null) return 0.7;
        String source = sourceType.toLowerCase(Locale.ROOT);
        if (source.contains("user")) return 0.95;
        if (source.contains("tool") || source.contains("system")) return 0.85;
        if (source.contains("llm")) return 0.65;
        return 0.75;
    }

    private record ScopeTarget(MemoryScope scope, String scopeId) {}
}
