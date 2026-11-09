package com.yin.ai.platform.memory.orchestration;

import java.util.List;

public record MemoryConsolidationResult(Status status, List<String> promotedIds,
                                        List<String> retainedIds, String reason) {
    public enum Status { COMPLETED, SKIPPED, RETRY_PENDING, FAILED }
    public MemoryConsolidationResult {
        promotedIds = promotedIds != null ? List.copyOf(promotedIds) : List.of();
        retainedIds = retainedIds != null ? List.copyOf(retainedIds) : List.of();
        reason = reason != null ? reason : "";
    }
}
