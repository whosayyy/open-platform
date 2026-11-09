package com.yin.ai.platform.memory.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 记忆维护调度器配置。
 */
@ConfigurationProperties("ai.platform.memory.maintenance")
public record MemoryMaintenanceConfig(
        boolean scheduledEnabled,
        Duration compactionInterval,
        Duration consolidationInterval,
        Duration evictionInterval,
        int compactionMaxCells,
        int evictionHighWatermark) {

    public static MemoryMaintenanceConfig defaults() {
        return new MemoryMaintenanceConfig(
                true,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                500,
                1000);
    }

    public long compactionIntervalMs() {
        return compactionInterval != null ? compactionInterval.toMillis() : 300_000;
    }

    public long consolidationIntervalMs() {
        return consolidationInterval != null ? consolidationInterval.toMillis() : 3_600_000;
    }

    public long evictionIntervalMs() {
        return evictionInterval != null ? evictionInterval.toMillis() : 1_800_000;
    }
}
