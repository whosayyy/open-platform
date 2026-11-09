package com.yin.ai.platform.memory.degradation;

import java.time.Duration;

/**
 * 降级策略 — 定义各 Memory 组件在故障时的行为。
 * <p>
 * 每个组件有独立的降级配置：
 * <ul>
 *   <li><b>Semantic Store</b>: MySQL 挂了 → 聊天继续，只是搜不到历史知识（SKIP）</li>
 *   <li><b>Embedding Service</b>: 超时 → 跳过 Vector 路径，只用 Keyword 检索（FALLBACK）</li>
 *   <li><b>Episodic Store</b>: Redis 挂了 → 降级到本地 InMemory（FALLBACK）</li>
 *   <li><b>Audit Store</b>: 写入失败 → 记录 WARN 日志，不阻塞（SKIP）</li>
 * </ul>
 *
 * @param componentName      组件名称
 * @param failureAction      故障时行为
 * @param timeout            超时阈值
 * @param maxConsecutiveFailures 连续失败 N 次后触发熔断
 * @param circuitBreakerOpenSeconds 熔断打开持续时间
 */
public record DegradationPolicy(
        String componentName,
        FailureAction failureAction,
        Duration timeout,
        int maxConsecutiveFailures,
        int circuitBreakerOpenSeconds) {

    public DegradationPolicy {
        if (timeout == null) timeout = Duration.ofSeconds(5);
        if (maxConsecutiveFailures <= 0) maxConsecutiveFailures = 5;
        if (circuitBreakerOpenSeconds <= 0) circuitBreakerOpenSeconds = 30;
    }

    public enum FailureAction {
        /** 抛出异常，让调用方处理 */
        FAIL_FAST,
        /** 跳过该组件，返回空/默认值 */
        SKIP,
        /** 降级到备用实现 */
        FALLBACK,
        /** 重试 N 次后再决定 */
        RETRY_THEN_SKIP
    }

    // ========== 预置策略 ==========

    public static DegradationPolicy semanticStore() {
        return new DegradationPolicy("semantic-store", FailureAction.SKIP,
                Duration.ofSeconds(5), 5, 30);
    }

    public static DegradationPolicy embeddingService() {
        return new DegradationPolicy("embedding-service", FailureAction.SKIP,
                Duration.ofSeconds(2), 3, 30);
    }

    public static DegradationPolicy episodicStore() {
        return new DegradationPolicy("episodic-store", FailureAction.FAIL_FAST,
                Duration.ofSeconds(3), 3, 15);
    }

    public static DegradationPolicy auditStore() {
        return new DegradationPolicy("audit-store", FailureAction.SKIP,
                Duration.ofSeconds(1), 10, 60);
    }

    public static DegradationPolicy compressionService() {
        return new DegradationPolicy("compression-service", FailureAction.SKIP,
                Duration.ofSeconds(10), 3, 60);
    }
}
