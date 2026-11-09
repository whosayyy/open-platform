package com.yin.ai.platform.agent.hitl;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.pattern.TaskStep;

/**
 * HITL 策略 SPI — 判断 Agent 当前步骤是否需要暂停等待人工审批。
 */
@FunctionalInterface
public interface HitlPolicy {

    /**
     * 在执行 Step 前调用，评估是否需要人工审批。
     *
     * @param step 即将执行的步骤
     * @param ctx  当前 Agent 上下文
     * @return 需要审批时返回 HitlCheckpoint，否则返回 null
     */
    HitlCheckpoint evaluate(Step step, AgentContext ctx);

    /** Agent V2 PlanExecute approval hook. */
    default HitlCheckpoint evaluate(TaskStep step, String taskId, int stepIndex) {
        return null;
    }

    /**
     * 在 Step 执行后、Observation 前调用，基于实际结果再评估。
     * 默认不触发。
     */
    default HitlCheckpoint evaluateAfter(Step step, StepResult result, AgentContext ctx) {
        return null;
    }
}
