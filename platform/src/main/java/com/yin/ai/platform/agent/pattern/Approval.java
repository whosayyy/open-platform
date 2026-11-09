package com.yin.ai.platform.agent.pattern;

import java.time.Instant;
import java.util.Map;

public record Approval(
        String approvalId, String taskId, String stepId, String executionId,
        String action, String riskLevel, Map<String, Object> context,
        Status status, String resolvedBy, String decisionReason,
        Instant requestedAt, Instant resolvedAt
) {
    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }
}
