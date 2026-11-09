package com.yin.ai.platform.memory.orchestration;

public interface MemoryConflictResolver {
    ConflictResolutionResult resolve(MemoryCandidate candidate, MemoryAccessContext context);
}
