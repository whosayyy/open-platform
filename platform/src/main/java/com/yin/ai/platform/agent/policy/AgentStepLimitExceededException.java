package com.yin.ai.platform.agent.policy;

/** Explicit terminal condition used when an Agent plan exceeds its effective step budget. */
public class AgentStepLimitExceededException extends IllegalStateException {

    private final int plannedSteps;
    private final int effectiveLimit;

    public AgentStepLimitExceededException(int plannedSteps, int effectiveLimit) {
        super("AGENT_MAX_STEPS_EXCEEDED: planned=" + plannedSteps + ", limit=" + effectiveLimit);
        this.plannedSteps = plannedSteps;
        this.effectiveLimit = effectiveLimit;
    }

    public int plannedSteps() {
        return plannedSteps;
    }

    public int effectiveLimit() {
        return effectiveLimit;
    }
}
