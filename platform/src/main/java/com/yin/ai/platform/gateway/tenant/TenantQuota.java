package com.yin.ai.platform.gateway.tenant;

import java.time.Instant;

public record TenantQuota(
        String tenantId,
        int maxSessions,
        int maxTokensPerDay,
        int maxConcurrentTasks,
        int maxRagDocuments,
        Instant updatedAt) {

    public static TenantQuota defaults(String tenantId) {
        return new TenantQuota(tenantId, 50, 1_000_000, 5, 100, Instant.now());
    }

    public boolean isUnlimited() {
        return maxSessions <= 0 && maxTokensPerDay <= 0
                && maxConcurrentTasks <= 0 && maxRagDocuments <= 0;
    }
}
