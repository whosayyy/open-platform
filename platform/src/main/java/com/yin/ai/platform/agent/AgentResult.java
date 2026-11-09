package com.yin.ai.platform.agent;

import com.yin.ai.platform.gateway.TokenUsage;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Agent 执行结果。
 *
 * @param success    是否成功
 * @param result     最终结果文本
 * @param trace      完整执行轨迹
 * @param tokenUsage Token 用量汇总
 * @param cost       预估费用（USD）
 * @param stepCount  已执行步骤数
 * @param error      错误信息（失败时）
 */
public record AgentResult(
        boolean success,
        String result,
        List<StepTrace> trace,
        TokenUsage tokenUsage,
        double cost,
        int stepCount,
        String error) {

    public AgentResult {
        trace = trace != null ? Collections.unmodifiableList(trace) : Collections.emptyList();
    }

    public static AgentResult ok(String result, List<StepTrace> trace, TokenUsage tokens, double cost, int steps) {
        return new AgentResult(true, result, trace, tokens, cost, steps, null);
    }

    public static AgentResult ok(String result, List<StepTrace> trace, int steps) {
        return new AgentResult(true, result, trace, new TokenUsage(), 0.0, steps, null);
    }

    /** Builds a successful result with the usage accumulated in AgentContext. */
    public static AgentResult ok(String result, List<StepTrace> trace, int steps, AgentContext context) {
        TokenUsage usage = new TokenUsage();
        if (context != null) {
            usage.setPromptTokens(context.getVariable("agent.promptTokens", Integer.class));
            usage.setCompletionTokens(context.getVariable("agent.completionTokens", Integer.class));
            usage.setTotalTokens(context.getVariable("agent.totalTokens", Integer.class));
        }
        return ok(result, trace, usage, 0.0, steps);
    }

    public static AgentResult fail(String error, List<StepTrace> trace, int steps) {
        return new AgentResult(false, null, trace, new TokenUsage(), 0.0, steps, error);
    }

    public static AgentResult cancelled(int steps) {
        return new AgentResult(false, null, Collections.emptyList(), new TokenUsage(), 0.0, steps, "cancelled");
    }

    public static AgentResult timeout(int steps, Duration limit) {
        return new AgentResult(false, null, Collections.emptyList(), new TokenUsage(), 0.0, steps,
                "timeout after " + limit.toSeconds() + "s");
    }

    /**
     * 超时但保留已完成的 step traces，不丢弃中间结果。
     */
    public static AgentResult timeout(int steps, List<StepTrace> trace, Duration limit) {
        return new AgentResult(false, null, trace, new TokenUsage(), 0.0, steps,
                "timeout after " + limit.toSeconds() + "s");
    }

    /**
     * 部分完成 — 任务因中断/超时未全部完成，但保留已执行步骤的结果。
     */
    public static AgentResult partial(String result, List<StepTrace> trace, int completedSteps) {
        return new AgentResult(true, result, trace, new TokenUsage(), 0.0, completedSteps,
                "partial completion");
    }

    public boolean partialResult() {
        return "partial completion".equals(error);
    }

    public boolean waiting() {
        return "awaiting_approval".equals(error);
    }

    /**
     * 等待 HITL 审批 — Agent 暂停执行，等待人工审批。
     */
    public static AgentResult waiting(String taskId, int steps, List<StepTrace> trace) {
        return new AgentResult(false, null, trace, new TokenUsage(), 0.0, steps,
                "awaiting_approval");
    }
}
