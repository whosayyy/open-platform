package com.yin.ai.platform.core.rag.query;

import java.util.List;

/** Produces additional retrieval queries; callers always retain the original query. */
public interface QueryExpansionStrategy {

    List<String> expand(String query, Long userId);

    default int priority() {
        return 1_000;
    }
}
