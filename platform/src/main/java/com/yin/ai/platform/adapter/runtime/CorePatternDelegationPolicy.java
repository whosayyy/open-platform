package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.core.agent.pattern.AgentMode;

import java.util.Set;

/** Immutable rollout policy for delegating legacy pattern execution to core. */
public record CorePatternDelegationPolicy(
        boolean enabled,
        Set<AgentMode> modes,
        String requestOverrideKey,
        int maxReActIterations,
        int maxPlanRounds,
        int maxStepRetries
) {
    public CorePatternDelegationPolicy {
        modes = modes == null ? Set.of() : Set.copyOf(modes);
        requestOverrideKey = requestOverrideKey == null || requestOverrideKey.isBlank()
                ? "coreDelegation" : requestOverrideKey.trim();
        maxReActIterations = Math.max(1, maxReActIterations);
        maxPlanRounds = Math.max(1, maxPlanRounds);
        maxStepRetries = Math.max(0, maxStepRetries);
    }

    public static CorePatternDelegationPolicy disabled() {
        return new CorePatternDelegationPolicy(false, Set.of(), "coreDelegation",
                8, 32, 2);
    }
}
