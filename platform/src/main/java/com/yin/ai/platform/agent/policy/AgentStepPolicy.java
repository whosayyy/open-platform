package com.yin.ai.platform.agent.policy;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import com.yin.ai.platform.rollout.RolloutMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Resolves request, Skill and platform Agent step limits at the shared Runtime boundary. */
@Component
public class AgentStepPolicy {

    public static final String REQUEST_KEY = "maxAgentSteps";
    public static final String SKILL_KEY = "skillMaxAgentSteps";

    private final RoadmapFeatureProperties rollout;
    private final MeterRegistry meters;

    @Autowired
    public AgentStepPolicy(RoadmapFeatureProperties rollout, ObjectProvider<MeterRegistry> meters) {
        this(rollout, meters.getIfAvailable());
    }

    public AgentStepPolicy(RoadmapFeatureProperties rollout, MeterRegistry meters) {
        this.rollout = rollout;
        this.meters = meters;
    }

    public Resolution resolve(Map<String, String> metadata) {
        Map<String, String> safe = metadata != null ? metadata : Map.of();
        int requested = positiveInt(safe.get(REQUEST_KEY), AgentConfig.DEFAULT_MAX_STEPS);
        int skillLimit = positiveInt(safe.get(SKILL_KEY), AgentConfig.DEFAULT_MAX_STEPS);
        return resolve(requested, skillLimit);
    }

    public Resolution resolve(Integer requested, SkillManifest.RulesConfig rules) {
        int skillLimit = rules != null ? rules.getMaxAgentSteps() : AgentConfig.DEFAULT_MAX_STEPS;
        return resolve(requested != null ? requested : AgentConfig.DEFAULT_MAX_STEPS, skillLimit);
    }

    public Resolution resolve(int requested, int skillLimit) {
        int normalizedRequest = requested > 0 ? requested : AgentConfig.DEFAULT_MAX_STEPS;
        int normalizedSkill = skillLimit > 0 ? skillLimit : AgentConfig.DEFAULT_MAX_STEPS;
        int hardCapped = Math.min(normalizedRequest, AgentConfig.PLATFORM_MAX_STEPS);
        int policyLimit = Math.min(hardCapped, Math.min(normalizedSkill, AgentConfig.PLATFORM_MAX_STEPS));
        RolloutMode mode = rollout != null ? rollout.getAgentConstraints() : RolloutMode.ENFORCE;
        int effective = mode.isEnforcing() ? policyLimit : hardCapped;
        boolean wouldConstrain = policyLimit < hardCapped;
        record(mode, wouldConstrain, effective);
        return new Resolution(normalizedRequest, normalizedSkill, AgentConfig.PLATFORM_MAX_STEPS,
                effective, policyLimit, wouldConstrain, mode);
    }

    private int positiveInt(String value, int fallback) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void record(RolloutMode mode, boolean constrained, int effective) {
        if (meters == null) return;
        meters.counter("ai.agent.constraint.decision", "mode", mode.name().toLowerCase(),
                "outcome", constrained ? "constrained" : "allowed").increment();
        meters.summary("ai.agent.max_steps.effective").record(effective);
    }

    public record Resolution(int requested, int skillLimit, int platformLimit,
                             int effectiveLimit, int enforcedLimit,
                             boolean wouldConstrain, RolloutMode mode) {}
}
