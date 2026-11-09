package com.yin.ai.platform.memory.semantic;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.memory.orchestration.ConflictResolutionResult;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;
import com.yin.ai.platform.memory.orchestration.MemoryCandidate;
import com.yin.ai.platform.memory.orchestration.MemoryWriteDecision;

import java.util.List;

/** Transactional temporal-fact application port. */
public interface TemporalSemanticMemoryService {
    ConflictResolutionResult remember(MemoryCandidate candidate,
                                      MemoryWriteDecision decision,
                                      MemoryAccessContext context);
    List<ScoredMemoryCell> retrieve(MemoryQuery query, MemoryAccessContext context);
}
