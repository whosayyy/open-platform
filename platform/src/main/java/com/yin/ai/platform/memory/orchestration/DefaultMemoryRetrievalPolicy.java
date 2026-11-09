package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Builds a bounded, context-aware retrieval plan before stores are queried. */
public class DefaultMemoryRetrievalPolicy implements MemoryRetrievalPolicy {
    private final int defaultTokenBudget;

    public DefaultMemoryRetrievalPolicy(int defaultTokenBudget) {
        this.defaultTokenBudget = Math.max(1, defaultTokenBudget);
    }

    @Override
    public MemoryRetrievalPlan plan(MemoryQuery query, MemoryAccessContext context) {
        MemoryQuery safeQuery = query != null ? query : MemoryQuery.byText("", 10);
        Set<MemoryType> types = safeQuery.searchTypes().isEmpty()
                ? inferTypes(safeQuery) : EnumSet.copyOf(safeQuery.searchTypes());
        // SESSION remains a read alias only. New session records are EPISODIC + SESSION scope.
        if (types.contains(MemoryType.SESSION)) types.add(MemoryType.EPISODIC);
        Set<MemoryScope> scopes = safeQuery.scopes().isEmpty()
                ? availableScopes(context) : EnumSet.copyOf(safeQuery.scopes());
        int tokenBudget = safeQuery.tokenBudget() > 0
                ? safeQuery.tokenBudget() : defaultTokenBudget;
        return new MemoryRetrievalPlan(types, scopes, safeQuery.topK(), tokenBudget,
                safeQuery.minScore(), true, true);
    }

    private Set<MemoryType> inferTypes(MemoryQuery query) {
        String intent = ((query.intent() != null ? query.intent() : "") + " "
                + (query.textQuery() != null ? query.textQuery() : ""))
                .toLowerCase(Locale.ROOT);
        if (containsAny(intent, "how", "procedure", "步骤", "怎么", "如何")) {
            return EnumSet.of(MemoryType.PROCEDURAL, MemoryType.SEMANTIC);
        }
        if (containsAny(intent, "previous", "why", "之前", "当时", "为什么", "决定")) {
            return EnumSet.of(MemoryType.EPISODIC, MemoryType.SEMANTIC, MemoryType.SESSION);
        }
        return EnumSet.of(MemoryType.EPISODIC, MemoryType.SEMANTIC);
    }

    private Set<MemoryScope> availableScopes(MemoryAccessContext context) {
        EnumSet<MemoryScope> scopes = EnumSet.noneOf(MemoryScope.class);
        if (context == null) return scopes;
        if (context.tenantId() != null) scopes.add(MemoryScope.TENANT);
        if (context.userId() != null) scopes.add(MemoryScope.USER);
        if (context.sessionId() != null) scopes.add(MemoryScope.SESSION);
        if (context.taskId() != null) scopes.add(MemoryScope.TASK);
        if (context.workflowId() != null) scopes.add(MemoryScope.WORKFLOW);
        if (context.agentId() != null) scopes.add(MemoryScope.AGENT);
        if (context.stepId() != null) scopes.add(MemoryScope.STEP);
        return scopes;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
