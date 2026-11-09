package com.yin.ai.platform.agent.pattern.reflect;

/**
 * 步骤反思决策 — Reflector 评估后的行动指令。
 */
public enum ReflectDecision {

    /** 继续执行下一步 */
    CONTINUE,

    /** 当前步骤需要重试（输出不符合预期） */
    RETRY,

    /** 需要修改后续计划（追加/删除/调整步骤） */
    REPLAN,

    /** 信息已充分，跳过剩余步骤直接进入综合阶段 */
    SKIP_TO_SYNTHESIS,

    /** 任务失败，终止执行 */
    ABORT
}
