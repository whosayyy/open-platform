package com.yin.ai.platform.core.retrieval;

import java.util.Map;

public record RetrievalQuery(String text, int limit, Map<String, Object> filters) {
    public RetrievalQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        limit = limit <= 0 ? 10 : limit;
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
