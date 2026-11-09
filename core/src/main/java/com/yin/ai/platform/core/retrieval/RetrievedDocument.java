package com.yin.ai.platform.core.retrieval;

import java.util.Map;

public record RetrievedDocument(
        String id,
        String content,
        double score,
        Map<String, Object> metadata
) {
    public RetrievedDocument {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
