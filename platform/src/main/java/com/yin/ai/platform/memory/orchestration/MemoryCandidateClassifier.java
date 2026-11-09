package com.yin.ai.platform.memory.orchestration;

import java.util.Optional;

/** Optional classifier used only when deterministic write rules cannot decide. */
@FunctionalInterface
public interface MemoryCandidateClassifier {
    Optional<MemoryWriteDecision> classify(MemoryCandidate candidate,
                                           MemoryAccessContext context);
}
