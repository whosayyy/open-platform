package com.yin.ai.platform.agent.pattern.executor;

/**
 * 步骤失败策略 — 按 executor 类型配置不同的失败处理方式。
 * <p>
 * 企业级设计：不是所有步骤失败都应该终止任务。
 * 搜索失败可以重试，LLM 生成失败可以跳过，关键步骤失败必须终止。
 */
public enum StepFailurePolicy {

    /** 重试（指数退避），最多 N 次 */
    RETRY,

    /** 跳过当前步骤，继续执行后续 */
    SKIP,

    /** 终止整个任务 */
    FAIL_TASK,

    /** 触发补偿（回滚已执行的副作用步骤） */
    COMPENSATE
}
