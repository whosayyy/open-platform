package com.yin.ai.platform.memory.orchestration;

public interface MemoryWritePolicy {
    MemoryWriteDecision evaluate(MemoryCandidate candidate, MemoryAccessContext context);
}
