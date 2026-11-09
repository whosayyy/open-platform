package com.yin.ai.platform.agent.pattern.reflect;

import com.yin.ai.platform.agent.pattern.TaskStep;

import java.util.List;

/**
 * 反思结果 — Reflector 对步骤执行结果的评估。
 *
 * @param decision    行动决策
 * @param reason      决策理由（供日志和前端展示）
 * @param newSteps    追加的步骤（仅 REPLAN 时非空）
 * @param skipStepIds 需要跳过的步骤 ID（仅 REPLAN 时）
 * @param retryHint   重试提示（仅 RETRY 时，注入下次执行的 Prompt）
 */
public record ReflectResult(
        ReflectDecision decision,
        String reason,
        List<TaskStep> newSteps,
        List<String> skipStepIds,
        String retryHint
) {
    public static ReflectResult continueExecution() {
        return new ReflectResult(ReflectDecision.CONTINUE, "output meets expectations",
                List.of(), List.of(), null);
    }

    public static ReflectResult retry(String hint) {
        return new ReflectResult(ReflectDecision.RETRY, "output quality insufficient",
                List.of(), List.of(), hint);
    }

    public static ReflectResult replan(String reason, List<TaskStep> newSteps) {
        return new ReflectResult(ReflectDecision.REPLAN, reason, newSteps, List.of(), null);
    }

    public static ReflectResult skipToSynthesis(String reason) {
        return new ReflectResult(ReflectDecision.SKIP_TO_SYNTHESIS, reason,
                List.of(), List.of(), null);
    }

    public static ReflectResult abort(String reason) {
        return new ReflectResult(ReflectDecision.ABORT, reason, List.of(), List.of(), null);
    }
}
