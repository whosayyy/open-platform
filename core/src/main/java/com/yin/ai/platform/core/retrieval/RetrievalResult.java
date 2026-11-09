package com.yin.ai.platform.core.retrieval;

import java.util.List;
import java.util.Map;

public record RetrievalResult(List<RetrievedDocument> documents,
                              Map<String, Object> metadata) {
    public RetrievalResult {
        documents = documents == null ? List.of() : List.copyOf(documents);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
