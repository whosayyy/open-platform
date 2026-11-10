package com.yin.ai.platform.gateway.cost;

import com.yin.ai.platform.gateway.TokenUsage;

/** Privacy-safe persistence event for tenant/skill/model daily cost aggregation. */
public record CostUsageEvent(
        String tenantId,
        String skill,
        String model,
        TokenUsage tokenUsage,
        double costUsd,
        boolean error,
        long latencyMs) {
}
