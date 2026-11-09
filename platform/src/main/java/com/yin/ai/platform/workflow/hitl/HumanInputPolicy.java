package com.yin.ai.platform.workflow.hitl;

import com.yin.ai.platform.workflow.executor.StepExecutionContext;
import com.yin.ai.platform.workflow.model.TaskRun;

/**
 * Human-in-the-loop 策略 — 默认自动执行全部阶段，仅特定条件暂停。
 *
 * <p>禁止依赖 Prompt 中"请确认后继续"实现暂停。
 */
public interface HumanInputPolicy {

    /**
     * 在步骤执行成功后评估是否需要人工输入。
     */
    HumanInputDecision evaluate(TaskRun task, StepExecutionContext context);
}
