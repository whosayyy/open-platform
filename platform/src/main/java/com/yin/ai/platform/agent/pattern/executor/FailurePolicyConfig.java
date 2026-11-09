package com.yin.ai.platform.agent.pattern.executor;

import java.util.Map;

/**
 * 失败策略配置 — 按 executor 类型映射失败处理方式。
 * <p>
 * 默认策略：
 * - llm → RETRY（最多 2 次）
 * - web_search → RETRY（最多 3 次，搜索不稳定）
 * - skill:* → SKIP（非关键步骤可跳过）
 * - tool:* → FAIL_TASK（工具调用失败通常意味着系统问题）
 * <p>
 * 可通过 Spring 配置覆盖。
 */
public record FailurePolicyConfig(
        StepFailurePolicy policy,
        int maxRetries,
        long retryDelayMs,
        boolean exponentialBackoff
) {
    public static FailurePolicyConfig retry(int maxRetries, long delayMs) {
        return new FailurePolicyConfig(StepFailurePolicy.RETRY, maxRetries, delayMs, true);
    }

    public static FailurePolicyConfig skip() {
        return new FailurePolicyConfig(StepFailurePolicy.SKIP, 0, 0, false);
    }

    public static FailurePolicyConfig failTask() {
        return new FailurePolicyConfig(StepFailurePolicy.FAIL_TASK, 0, 0, false);
    }

    /** 默认配置映射 */
    public static Map<String, FailurePolicyConfig> defaults() {
        return Map.of(
                "llm", retry(2, 1000),
                "web_search", retry(3, 2000),
                "skill", skip(),
                "tool", failTask()
        );
    }
}
