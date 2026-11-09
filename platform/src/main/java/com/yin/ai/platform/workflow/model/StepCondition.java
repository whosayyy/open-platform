package com.yin.ai.platform.workflow.model;

/**
 * 条件步骤的触发条件。
 *
 * @param type       条件类型，如 "explicit_publish"（由平台端评估，不由模型判断）
 * @param expression 条件表达式或参数
 */
public record StepCondition(String type, String expression) {

    public boolean isExplicitPublish() {
        return "explicit_publish".equals(type);
    }

    /**
     * 无条件的步骤始终执行。
     */
    public static StepCondition always() {
        return new StepCondition("always", null);
    }
}
