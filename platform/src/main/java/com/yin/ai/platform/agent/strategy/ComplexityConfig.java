package com.yin.ai.platform.agent.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 复杂度分析配置。
 */
@ConfigurationProperties("ai.platform.agent.complexity")
public record ComplexityConfig(
        boolean enabled,
        double simpleThreshold,
        double moderateThreshold,
        boolean llmAnalyzerEnabled,
        Map<ComplexityLevel, ExecutionStrategy> strategyMapping) {

    public static ComplexityConfig defaults() {
        return new ComplexityConfig(
                true,
                0.7,
                0.5,
                false,
                Map.of(
                        // 【企业级缺口修复】默认 SIMPLE 走 REACT_LOOP（保持现有行为防回归）；
                        // DIRECT_SKILL 是显式 opt-in——配置 strategy-mapping.SIMPLE=DIRECT_SKILL 启用快捷路径
                        ComplexityLevel.SIMPLE, ExecutionStrategy.REACT_LOOP,
                        ComplexityLevel.MODERATE, ExecutionStrategy.REACT_LOOP,
                        ComplexityLevel.COMPLEX, ExecutionStrategy.REACT_LOOP));
    }

    public double simpleThreshold() {
        return simpleThreshold > 0 ? simpleThreshold : 0.7;
    }

    public double moderateThreshold() {
        return moderateThreshold > 0 ? moderateThreshold : 0.5;
    }
}
