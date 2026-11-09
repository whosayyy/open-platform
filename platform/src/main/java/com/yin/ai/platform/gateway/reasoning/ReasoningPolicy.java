package com.yin.ai.platform.gateway.reasoning;

import com.yin.ai.platform.agent.pattern.AgentMode;
import com.yin.ai.platform.agent.pattern.ReasoningMode;
import com.yin.ai.platform.agent.pattern.StepType;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.gateway.model.ModelCapabilities;

import java.util.List;

/** Resolves requested reasoning into a capability-safe effective mode. */
public class ReasoningPolicy {

    private static final List<ReasoningMode> DESCENDING = List.of(
            ReasoningMode.HIGH, ReasoningMode.MEDIUM, ReasoningMode.LOW, ReasoningMode.OFF);

    public ModelExecutionOptions resolve(AgentMode agentMode, StepType stepType,
                                         ReasoningMode requested, ModelCapabilities capabilities) {
        ReasoningMode userMode = requested == null ? ReasoningMode.AUTO : requested;
        ModelCapabilities caps = capabilities == null ? ModelCapabilities.standard() : capabilities;
        ReasoningMode target = userMode == ReasoningMode.AUTO
                ? autoTarget(agentMode, stepType) : userMode;
        ReasoningMode effective = highestSupportedAtOrBelow(target, caps);
        String fallback = effective == target ? null
                : "Model does not support " + target + "; using " + effective;
        if (fallback != null) {
            io.micrometer.core.instrument.Metrics.counter("ai.agent.reasoning.downgrade",
                    "agent_mode", (agentMode == null ? AgentMode.REACT : agentMode).name(),
                    "requested", target.name(), "effective", effective.name()).increment();
        }
        return new ModelExecutionOptions(userMode, effective, fallback);
    }

    private ReasoningMode autoTarget(AgentMode mode, StepType type) {
        if (mode == AgentMode.DIRECT) return ReasoningMode.OFF;
        if (mode == AgentMode.DEEP_RESEARCH || mode == AgentMode.MULTI_AGENT) return ReasoningMode.HIGH;
        if (type == StepType.PLANNING || type == StepType.EVIDENCE_ANALYSIS
                || type == StepType.SYNTHESIS || type == StepType.FACT_CHECK) {
            return ReasoningMode.HIGH;
        }
        if (type == StepType.SEARCH || type == StepType.RAG_RETRIEVAL || type == StepType.TOOL) {
            return ReasoningMode.LOW;
        }
        return mode == AgentMode.PLAN_EXECUTE ? ReasoningMode.MEDIUM : ReasoningMode.LOW;
    }

    private ReasoningMode highestSupportedAtOrBelow(ReasoningMode target, ModelCapabilities caps) {
        if (target == ReasoningMode.OFF || !caps.reasoning()) return ReasoningMode.OFF;
        int start = DESCENDING.indexOf(target);
        if (start < 0) start = DESCENDING.size() - 1;
        for (int i = start; i < DESCENDING.size(); i++) {
            ReasoningMode candidate = DESCENDING.get(i);
            if (caps.supports(candidate)) return candidate;
        }
        return ReasoningMode.OFF;
    }
}
