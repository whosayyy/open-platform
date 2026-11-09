package com.yin.ai.platform.agent.pattern;

/**
 * 执行计划中单个步骤的状态。
 */
public enum StepStatus {

    /** 待执行 */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 已完成 */
    COMPLETED,

    /** 执行失败 */
    FAILED,

    /** 已跳过（依赖失败或策略决定跳过） */
    SKIPPED
}
