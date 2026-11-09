package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.api.MemoryQuery;

public interface MemoryOrchestrator {
    MemoryWriteResult remember(MemoryCandidate candidate, MemoryAccessContext context);
    MemoryContextBundle retrieve(MemoryQuery query, MemoryAccessContext context);
    MemoryConsolidationResult consolidate(ConsolidationRequest request);
    ForgetResult forget(ForgetRequest request, MemoryAccessContext context);
    ConflictResolutionResult resolveConflict(MemoryCandidate candidate,
                                             MemoryAccessContext context);
}
