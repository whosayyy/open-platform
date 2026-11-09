package com.yin.ai.platform.memory.semantic;

import com.yin.ai.platform.core.memory.semantic.SemanticFact;
import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;

import java.util.List;

/** Elasticsearch projection port. Implementations return candidate IDs only. */
public interface SemanticFactProjection {
    int expectedDimension();
    boolean isAvailable();
    void upsert(SemanticFact fact);
    void delete(long factId);
    List<Long> searchCandidateIds(MemoryQuery query, MemoryAccessContext context, int limit);
}
