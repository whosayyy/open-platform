package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Duration;
import java.util.Map;

/**
 * 保留策略 — 控制各 Memory 类型的 TTL。
 * <p>
 * 每条策略按 MemoryType 映射到不同的 TTL，未配置的类型使用默认值。
 * 注意：SESSION TTL 由 {@link com.yin.ai.platform.memory.SessionMemoryManager} 管理（30min），
 * 本策略主要控制 EPISODIC 和 WORKING 类型的长期保留。
 *
 * @param sessionTtl          Session 记忆 TTL（默认 30min）
 * @param episodicTtl         Episodic 记忆 TTL（默认 7 天）
 * @param workingTtl          Working 记忆 TTL（默认 1 小时）
 * @param checkpointRetention Checkpoint 保留时长（默认 30 天）
 * @param customTtls          自定义类型 TTL 映射
 */
public record RetentionPolicy(
        Duration sessionTtl,
        Duration episodicTtl,
        Duration workingTtl,
        Duration checkpointRetention,
        Map<MemoryType, Duration> customTtls) {

    public RetentionPolicy {
        sessionTtl = sessionTtl != null ? sessionTtl : Duration.ofMinutes(30);
        episodicTtl = episodicTtl != null ? episodicTtl : Duration.ofDays(7);
        workingTtl = workingTtl != null ? workingTtl : Duration.ofHours(1);
        checkpointRetention = checkpointRetention != null ? checkpointRetention : Duration.ofDays(30);
        customTtls = customTtls != null ? Map.copyOf(customTtls) : Map.of();
    }

    public static RetentionPolicy defaults() {
        return new RetentionPolicy(null, null, null, null, Map.of());
    }

    /** 获取指定 MemoryType 的 TTL */
    public Duration ttlFor(MemoryType type) {
        return switch (type) {
            case SESSION -> sessionTtl;
            case EPISODIC -> episodicTtl;
            case WORKING -> workingTtl;
            case SEMANTIC -> customTtls.getOrDefault(MemoryType.SEMANTIC, Duration.ofDays(365));
            case PROCEDURAL -> customTtls.getOrDefault(MemoryType.PROCEDURAL, Duration.ofDays(365));
        };
    }
}
