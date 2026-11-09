package com.yin.ai.platform.agent.runtime;

import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.StepTrace;
import com.yin.ai.platform.gateway.TokenUsage;

import java.util.List;

/**
 * SubAgent 执行结果 — 从 AgentResult 提取，附加 SubAgent 标识。
 */
public record SubAgentResult(
        String agentId,
        String result,
        List<StepTrace> trace,
        TokenUsage tokenUsage,
        double cost,
        int stepCount) {

    public static SubAgentResult of(String agentId, AgentResult r) {
        return new SubAgentResult(agentId, r.result(), r.trace(), r.tokenUsage(), r.cost(), r.stepCount());
    }

    public static SubAgentResult empty(String agentId) {
        return new SubAgentResult(agentId, "", List.of(), new TokenUsage(), 0.0, 0);
    }
}
