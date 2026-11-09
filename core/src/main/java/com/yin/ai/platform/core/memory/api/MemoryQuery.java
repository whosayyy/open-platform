package com.yin.ai.platform.core.memory.api;

import com.yin.ai.platform.core.memory.MemorySpan;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.MemoryVector;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable keyword/vector/hybrid memory query with scope and context limits. */
public record MemoryQuery(
        String textQuery,
        MemoryVector vectorQuery,
        List<MemoryType> searchTypes,
        MemorySpan timeRange,
        List<String> tags,
        int topK,
        double minScore,
        Map<String, Object> filters,
        Set<MemoryScope> scopes,
        String intent,
        int tokenBudget) {

    public MemoryQuery {
        searchTypes = searchTypes != null ? List.copyOf(searchTypes) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
        filters = filters != null ? Map.copyOf(filters) : Map.of();
        scopes = scopes != null ? Set.copyOf(scopes) : Set.of();
        topK = topK > 0 ? topK : 10;
        minScore = Math.max(0, minScore);
        tokenBudget = Math.max(0, tokenBudget);
    }

    /** Source-compatible constructor retained for existing callers. */
    public MemoryQuery(String textQuery, MemoryVector vectorQuery,
                       List<MemoryType> searchTypes, MemorySpan timeRange,
                       List<String> tags, int topK, double minScore,
                       Map<String, Object> filters) {
        this(textQuery, vectorQuery, searchTypes, timeRange, tags, topK,
                minScore, filters, Set.of(), null, 0);
    }

    public static MemoryQuery byText(String query, int topK) {
        return new MemoryQuery(query, null, List.of(), null, List.of(), topK, 0, Map.of());
    }

    public static MemoryQuery byVector(MemoryVector vector, int topK) {
        return new MemoryQuery(null, vector, List.of(), null, List.of(), topK, 0.5, Map.of());
    }

    public static MemoryQuery hybrid(String query, MemoryVector vector, int topK) {
        return new MemoryQuery(query, vector, List.of(), null, List.of(), topK, 0.3, Map.of());
    }

    public MemoryQuery withTypeFilter(MemoryType... types) {
        return copy(vectorQuery, List.of(types), timeRange, topK, scopes, intent, tokenBudget);
    }

    public MemoryQuery withTimeRange(MemorySpan span) {
        return copy(vectorQuery, searchTypes, span, topK, scopes, intent, tokenBudget);
    }

    public MemoryQuery withTopK(int newTopK) {
        return copy(vectorQuery, searchTypes, timeRange, newTopK, scopes, intent, tokenBudget);
    }

    public MemoryQuery withVectorQuery(MemoryVector vector) {
        return copy(vector, searchTypes, timeRange, topK, scopes, intent, tokenBudget);
    }

    public MemoryQuery withScopes(Set<MemoryScope> newScopes) {
        return copy(vectorQuery, searchTypes, timeRange, topK, newScopes, intent, tokenBudget);
    }

    public MemoryQuery withIntent(String newIntent) {
        return copy(vectorQuery, searchTypes, timeRange, topK, scopes, newIntent, tokenBudget);
    }

    public MemoryQuery withTokenBudget(int newTokenBudget) {
        return copy(vectorQuery, searchTypes, timeRange, topK, scopes, intent, newTokenBudget);
    }

    private MemoryQuery copy(MemoryVector vector, List<MemoryType> types,
                             MemorySpan span, int newTopK, Set<MemoryScope> newScopes,
                             String newIntent, int newTokenBudget) {
        return new MemoryQuery(textQuery, vector, types, span, tags, newTopK,
                minScore, filters, newScopes, newIntent, newTokenBudget);
    }
}
