package com.yin.ai.platform.gateway.tenant;

import java.util.List;
import java.util.Optional;

public interface TenantQuotaStore {

    Optional<TenantQuota> getQuota(String tenantId);

    void saveQuota(TenantQuota quota);

    void deleteQuota(String tenantId);

    List<TenantQuota> listAll();

    /** 检查租户是否超出某个维度的配额 */
    default boolean isExceeded(String tenantId, String dimension, long currentValue) {
        TenantQuota quota = getQuota(tenantId).orElse(null);
        if (quota == null || quota.isUnlimited()) return false;
        return switch (dimension) {
            case "sessions" -> currentValue >= quota.maxSessions();
            case "tokens" -> currentValue >= quota.maxTokensPerDay();
            case "concurrentTasks" -> currentValue >= quota.maxConcurrentTasks();
            case "ragDocuments" -> currentValue >= quota.maxRagDocuments();
            default -> false;
        };
    }
}
