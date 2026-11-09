package com.yin.ai.platform.memory.maintenance;

import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemoryStats;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Memory 健康检查 — 暴露到 Spring Boot Actuator /health 端点。
 * <p>
 * 检查项：
 * <ul>
 *   <li>MemoryRuntime 是否可达</li>
 *   <li>Cell 总数是否超过高水位</li>
 *   <li>活跃 WorkingMemory 数量</li>
 * </ul>
 */
public class MemoryHealthIndicator implements HealthIndicator {

    private final MemoryRuntime memoryRuntime;
    private final MemoryMaintenanceConfig config;

    public MemoryHealthIndicator(MemoryRuntime memoryRuntime, MemoryMaintenanceConfig config) {
        this.memoryRuntime = memoryRuntime;
        this.config = config;
    }

    @Override
    public Health health() {
        try {
            MemoryStats stats = memoryRuntime.stats();
            Health.Builder builder = Health.up()
                    .withDetail("totalCells", stats.totalCells())
                    .withDetail("totalMemories", stats.totalMemories())
                    .withDetail("activeWorkingMemories", stats.activeWorkingMemories())
                    .withDetail("totalSizeBytes", stats.totalSizeBytes());

            if (stats.totalCells() > config.evictionHighWatermark()) {
                builder.withDetail("warning", "cell count exceeds high watermark");
            }

            return builder.build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
