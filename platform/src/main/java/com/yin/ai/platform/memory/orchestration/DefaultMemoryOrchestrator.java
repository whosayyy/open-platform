package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;
import com.yin.ai.platform.memory.semantic.TemporalSemanticMemoryService;
import com.yin.ai.platform.memory.observe.MemoryMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default policy/store coordinator; adapters remain outside this class. */
public class DefaultMemoryOrchestrator implements MemoryOrchestrator {
    private final MemoryRuntime runtime;
    private final MemoryWritePolicy writePolicy;
    private final MemoryRetrievalPolicy retrievalPolicy;
    private final MemoryAccessPolicy accessPolicy;
    private final DefaultMemoryContextBuilder contextBuilder;
    private final TemporalSemanticMemoryService semanticService;
    private final MemoryConsolidationService consolidationService;
    private final MemoryForgetService forgetService;
    private final boolean shadowMode;
    private MemoryMetrics metrics;
    private final io.opentelemetry.api.trace.Tracer tracer;

    public DefaultMemoryOrchestrator(MemoryRuntime runtime,
                                     MemoryWritePolicy writePolicy,
                                     MemoryRetrievalPolicy retrievalPolicy,
                                     MemoryAccessPolicy accessPolicy,
                                     DefaultMemoryContextBuilder contextBuilder,
                                     TemporalSemanticMemoryService semanticService,
                                     MemoryConsolidationService consolidationService,
                                     MemoryForgetService forgetService,
                                     boolean shadowMode) {
        this(runtime, writePolicy, retrievalPolicy, accessPolicy, contextBuilder,
                semanticService, consolidationService, forgetService, shadowMode, null);
    }

    public DefaultMemoryOrchestrator(MemoryRuntime runtime,
                                     MemoryWritePolicy writePolicy,
                                     MemoryRetrievalPolicy retrievalPolicy,
                                     MemoryAccessPolicy accessPolicy,
                                     DefaultMemoryContextBuilder contextBuilder,
                                     TemporalSemanticMemoryService semanticService,
                                     MemoryConsolidationService consolidationService,
                                     MemoryForgetService forgetService,
                                     boolean shadowMode,
                                     io.opentelemetry.api.trace.Tracer tracer) {
        this.runtime = runtime;
        this.writePolicy = writePolicy;
        this.retrievalPolicy = retrievalPolicy;
        this.accessPolicy = accessPolicy;
        this.contextBuilder = contextBuilder;
        this.semanticService = semanticService;
        this.consolidationService = consolidationService;
        this.forgetService = forgetService;
        this.shadowMode = shadowMode;
        this.tracer = tracer;
    }

    public void setMetrics(MemoryMetrics metrics) { this.metrics = metrics; }

    @Override
    public MemoryWriteResult remember(MemoryCandidate candidate, MemoryAccessContext context) {
        io.opentelemetry.api.trace.Span span = startSpan("memory.remember");
        try {
        MemoryWriteDecision decision = writePolicy.evaluate(candidate, context);
        if (!decision.shouldRemember()) {
            recordRemember(decision, "rejected");
            return MemoryWriteResult.rejected(decision);
        }
        MemoryAccessPolicy.AccessDecision access = accessPolicy.authorizeWrite(
                decision.scope(), decision.scopeId(), context != null ? context.tenantId() : null,
                context != null ? context.userId() : null, context);
        if (!access.allowed()) {
            recordRemember(decision, "denied");
            return new MemoryWriteResult(MemoryWriteResult.Status.REJECTED, decision,
                    List.of(), access.reason());
        }
        if (shadowMode) {
            recordRemember(decision, "shadowed");
            return new MemoryWriteResult(MemoryWriteResult.Status.SHADOWED, decision,
                    List.of(), "shadow decision: " + decision.reason());
        }
        if (decision.type() == MemoryType.EPISODIC
                && Boolean.TRUE.equals(candidate.metadata().get("rawEpisodeStored"))) {
            recordRemember(decision, "stored");
            return new MemoryWriteResult(MemoryWriteResult.Status.STORED, decision,
                    candidate.sourceId() != null ? List.of(candidate.sourceId()) : List.of(),
                    "raw episode already persisted by compatibility facade");
        }
        try {
            if (decision.type() == MemoryType.SEMANTIC && semanticService != null) {
                ConflictResolutionResult result = semanticService.remember(candidate, decision, context);
                recordRemember(decision, "stored");
                return new MemoryWriteResult(MemoryWriteResult.Status.STORED, decision,
                        result.affectedIds(), result.reason());
            }
            MemoryId id = MemoryId.of(decision.scopeId(), normalize(decision.type()), context.userId());
            MemoryCell stored = runtime.append(id, candidate.content(), attributes(candidate, decision, context),
                    legacyContext(context));
            recordRemember(decision, "stored");
            return new MemoryWriteResult(MemoryWriteResult.Status.STORED, decision,
                    List.of(stored.cellId().id()), decision.reason());
        } catch (RuntimeException exception) {
            recordRemember(decision, "failed");
            return new MemoryWriteResult(MemoryWriteResult.Status.FAILED, decision,
                    List.of(), exception.getMessage());
        }
        } finally {
            span.end();
        }
    }

    @Override
    public MemoryContextBundle retrieve(MemoryQuery query, MemoryAccessContext context) {
        io.opentelemetry.api.trace.Span span = startSpan("memory.retrieve");
        try {
        long started = System.currentTimeMillis();
        MemoryRetrievalPlan plan = retrievalPolicy.plan(query, context);
        if (context == null || context.tenantId() == null || context.userId() == null) {
            return MemoryContextBundle.empty(plan);
        }
        for (MemoryScope scope : plan.scopes()) {
            String scopeId = scopeId(scope, context);
            MemoryAccessPolicy.AccessDecision access = accessPolicy.authorizeRead(
                    scope, scopeId, context.tenantId(), context.userId(), context);
            if (!access.allowed()) return MemoryContextBundle.empty(plan);
        }

        MemoryQuery effective = withPlan(query, plan, context);
        List<ScoredMemoryCell> candidates = new ArrayList<>();
        if (isLegacyUserCompatible(context.userId())) {
            QueryResult legacy = runtime.retrieve(effective, legacyContext(context));
            if (legacy != null) candidates.addAll(legacy.results());
        }
        if (semanticService != null && plan.memoryTypes().contains(MemoryType.SEMANTIC)) {
            candidates.addAll(semanticService.retrieve(effective, context));
        }
        LinkedHashMap<String, ScoredMemoryCell> unique = new LinkedHashMap<>();
        candidates.stream().filter(value -> visible(value, plan, context)).forEach(value -> unique.merge(
                value.cell().cellId().id(), value,
                (left, right) -> left.score() >= right.score() ? left : right));
        MemoryContextBundle bundle = contextBuilder.build(plan, List.copyOf(unique.values()));
        if (metrics != null) metrics.recordRetrieval("success", bundle.memories().size(),
                bundle.actualTokens(), System.currentTimeMillis() - started);
        return bundle;
        } finally {
            span.end();
        }
    }

    @Override
    public MemoryConsolidationResult consolidate(ConsolidationRequest request) {
        io.opentelemetry.api.trace.Span span = startSpan("memory.consolidate");
        try {
        if (consolidationService == null) {
            return new MemoryConsolidationResult(MemoryConsolidationResult.Status.SKIPPED,
                    List.of(), List.of(), "consolidation adapter unavailable");
        }
        MemoryConsolidationResult result = consolidationService.consolidate(request);
        if (metrics != null) metrics.recordConsolidation(result.status().name(), result.promotedIds().size());
        return result;
        } finally {
            span.end();
        }
    }

    @Override
    public ForgetResult forget(ForgetRequest request, MemoryAccessContext context) {
        io.opentelemetry.api.trace.Span span = startSpan("memory.forget");
        try {
        if (forgetService == null) {
            return new ForgetResult(ForgetResult.Status.FAILED, null, Map.of(),
                    "forget adapter unavailable");
        }
        ForgetResult result = forgetService.forget(request, context);
        if (metrics != null) {
            result.deletedByStore().forEach((store, count) ->
                    metrics.recordForget(result.status().name(), store, count));
        }
        return result;
        } finally {
            span.end();
        }
    }

    @Override
    public ConflictResolutionResult resolveConflict(MemoryCandidate candidate,
                                                     MemoryAccessContext context) {
        io.opentelemetry.api.trace.Span span = startSpan("memory.resolve_conflict");
        try {
        MemoryWriteDecision decision = writePolicy.evaluate(candidate, context);
        if (!decision.shouldRemember() || decision.type() != MemoryType.SEMANTIC
                || semanticService == null) {
            return new ConflictResolutionResult(null, null, List.of(),
                    "candidate is not an eligible semantic fact");
        }
        ConflictResolutionResult result = semanticService.remember(candidate, decision, context);
        if (metrics != null) metrics.recordConflict(
                result.relation() != null ? result.relation().name() : "unknown", "resolved");
        return result;
        } finally {
            span.end();
        }
    }

    private MemoryQuery withPlan(MemoryQuery query, MemoryRetrievalPlan plan,
                                 MemoryAccessContext context) {
        MemoryQuery base = query != null ? query : MemoryQuery.byText("", plan.maxItems());
        List<MemoryType> types = new ArrayList<>(plan.memoryTypes());
        if (plan.scopes().contains(MemoryScope.SESSION) && !types.contains(MemoryType.SESSION)) {
            types.add(MemoryType.SESSION);
        }
        return new MemoryQuery(base.textQuery(), base.vectorQuery(), types, base.timeRange(),
                base.tags(), Math.max(plan.maxItems() * 3, plan.maxItems()), plan.minScore(),
                base.filters(), plan.scopes(), base.intent(), plan.tokenBudget());
    }

    private Map<String, Object> attributes(MemoryCandidate candidate,
                                           MemoryWriteDecision decision,
                                           MemoryAccessContext context) {
        Map<String, Object> attributes = new HashMap<>(candidate.metadata());
        attributes.put("tenantId", context.tenantId());
        attributes.put("userId", context.userId());
        attributes.put("memoryScope", decision.scope().name());
        attributes.put("scopeId", decision.scopeId());
        attributes.put("importance", decision.importance());
        attributes.put("confidence", decision.confidence());
        attributes.put("sourceType", candidate.sourceType());
        if (candidate.sourceId() != null) attributes.put("sourceId", candidate.sourceId());
        if (candidate.subject() != null) attributes.put("subject", candidate.subject());
        if (candidate.predicate() != null) attributes.put("predicate", candidate.predicate());
        attributes.values().removeIf(java.util.Objects::isNull);
        return attributes;
    }

    private MemoryContext legacyContext(MemoryAccessContext context) {
        Long numericUser = null;
        try {
            if (context.userId() != null) numericUser = Long.valueOf(context.userId());
        } catch (NumberFormatException ignored) {
            // The legacy facade can only express numeric users; persistent adapters use strings.
        }
        Map<String, Object> attributes = new HashMap<>();
        put(attributes, "tenantId", context.tenantId());
        put(attributes, "sessionId", context.sessionId());
        put(attributes, "taskId", context.taskId());
        put(attributes, "workflowId", context.workflowId());
        put(attributes, "stepId", context.stepId());
        put(attributes, "roles", context.roles());
        put(attributes, "permissions", context.permissions());
        return new MemoryContext(context.traceId(), context.sessionId(), numericUser,
                context.agentId(), "memory.orchestrator", Instant.now(), attributes);
    }

    private boolean visible(ScoredMemoryCell value, MemoryRetrievalPlan plan,
                            MemoryAccessContext context) {
        String status = String.valueOf(value.cell().attributes().getOrDefault("status", "ACTIVE"));
        if ("DELETED".equalsIgnoreCase(status)
                || "SUPERSEDED".equalsIgnoreCase(status)
                || "CONFLICTED".equalsIgnoreCase(status)) return false;
        Map<String, Object> attributes = value.cell().attributes();
        String tenantId = string(attributes.get("tenantId"), context.tenantId());
        String userId = string(attributes.get("userId"), context.userId());
        MemoryScope scope = scope(value, attributes);
        if (!plan.scopes().contains(scope)) return false;
        String targetScopeId = string(attributes.get("scopeId"),
                inferredScopeId(scope, attributes, context));
        return accessPolicy.authorizeRead(scope, targetScopeId,
                tenantId, userId, context).allowed();
    }

    private MemoryScope scope(ScoredMemoryCell value, Map<String, Object> attributes) {
        Object configured = attributes.get("memoryScope");
        if (configured != null) {
            try { return MemoryScope.valueOf(configured.toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        return value.sourceType() == MemoryType.SESSION ? MemoryScope.SESSION : MemoryScope.USER;
    }

    private String inferredScopeId(MemoryScope scope, Map<String, Object> attributes,
                                   MemoryAccessContext context) {
        if (scope == MemoryScope.SESSION && attributes.get("sessionId") != null) {
            return attributes.get("sessionId").toString();
        }
        return scopeId(scope, context);
    }

    private String string(Object value, String fallback) {
        return value != null && !value.toString().isBlank() ? value.toString() : fallback;
    }

    private MemoryType normalize(MemoryType type) {
        return type == MemoryType.SESSION ? MemoryType.EPISODIC : type;
    }

    private String scopeId(MemoryScope scope, MemoryAccessContext context) {
        return switch (scope) {
            case GLOBAL -> null;
            case TENANT -> context.tenantId();
            case USER -> context.userId();
            case SESSION -> context.sessionId();
            case TASK -> context.taskId();
            case WORKFLOW -> context.workflowId();
            case AGENT -> context.agentId();
            case STEP -> context.stepId();
        };
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private void recordRemember(MemoryWriteDecision decision, String outcome) {
        if (metrics != null) metrics.recordRemember(
                decision.type() != null ? decision.type().name() : "unknown",
                decision.scope() != null ? decision.scope().name() : "unknown", outcome);
    }

    private boolean isLegacyUserCompatible(String userId) {
        if (userId == null) return false;
        try {
            Long.parseLong(userId);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private io.opentelemetry.api.trace.Span startSpan(String name) {
        return tracer != null ? tracer.spanBuilder(name).startSpan()
                : io.opentelemetry.api.trace.Span.getInvalid();
    }
}
