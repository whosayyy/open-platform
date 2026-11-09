package com.yin.ai.platform.memory.strategy;

import com.yin.ai.platform.agent.strategy.ComplexityLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 记忆策略配置。
 */
@ConfigurationProperties("ai.platform.memory.strategy")
public record MemoryStrategyConfig(
        boolean enabled,
        Map<ComplexityLevel, MemoryTypeDecision> defaultDecisions) {

    public static MemoryStrategyConfig defaults() {
        return new MemoryStrategyConfig(true, Map.of(
                ComplexityLevel.SIMPLE, MemoryTypeDecision.sessionOnly(3),
                ComplexityLevel.MODERATE, MemoryTypeDecision.moderate(5),
                ComplexityLevel.COMPLEX, MemoryTypeDecision.full(10)));
    }
}
