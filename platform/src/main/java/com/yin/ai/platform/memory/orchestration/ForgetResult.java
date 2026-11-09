package com.yin.ai.platform.memory.orchestration;

import java.util.Map;

public record ForgetResult(Status status, String operationId,
                           Map<String, Integer> deletedByStore, String reason) {
    public enum Status { COMPLETED, PENDING, FAILED, DENIED }
    public ForgetResult {
        deletedByStore = deletedByStore != null ? Map.copyOf(deletedByStore) : Map.of();
        reason = reason != null ? reason : "";
    }
}
