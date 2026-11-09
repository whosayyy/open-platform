package com.yin.ai.platform.agent.strategy;

/**
 * 复杂度评估结果。
 *
 * @param level     复杂度等级
 * @param confidence 置信度 0.0 ~ 1.0
 * @param reason     可解释的判定理由
 * @param suggested  建议的执行策略
 */
public record ComplexityAssessment(
        ComplexityLevel level,
        double confidence,
        String reason,
        ExecutionStrategy suggested) {

    public static ComplexityAssessment simple(String reason) {
        return new ComplexityAssessment(ComplexityLevel.SIMPLE, 0.9, reason, ExecutionStrategy.DIRECT_SKILL);
    }

    public static ComplexityAssessment moderate(String reason) {
        return new ComplexityAssessment(ComplexityLevel.MODERATE, 0.8, reason, ExecutionStrategy.REACT_LOOP);
    }

    public static ComplexityAssessment complex(String reason) {
        return new ComplexityAssessment(ComplexityLevel.COMPLEX, 0.85, reason, ExecutionStrategy.REACT_LOOP);
    }
}
