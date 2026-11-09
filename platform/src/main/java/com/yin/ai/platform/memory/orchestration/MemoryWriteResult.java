package com.yin.ai.platform.memory.orchestration;

import java.util.List;

/** Result of a policy-governed remember operation. */
public record MemoryWriteResult(Status status, MemoryWriteDecision decision,
                                List<String> affectedIds, String reason) {
    public enum Status { STORED, REJECTED, SHADOWED, FAILED }

    public MemoryWriteResult {
        affectedIds = affectedIds != null ? List.copyOf(affectedIds) : List.of();
        reason = reason != null ? reason : "";
    }

    public static MemoryWriteResult rejected(MemoryWriteDecision decision) {
        return new MemoryWriteResult(Status.REJECTED, decision, List.of(), decision.reason());
    }
}
