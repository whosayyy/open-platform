package com.yin.ai.platform.agent.policy;

import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import com.yin.ai.platform.rollout.RolloutMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStepPolicyTest {

    @Test
    void enforceAppliesRequestSkillAndPlatformLimits() {
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setAgentConstraints(RolloutMode.ENFORCE);
        AgentStepPolicy policy = new AgentStepPolicy(rollout, new SimpleMeterRegistry());

        AgentStepPolicy.Resolution resolution = policy.resolve(40, 10);

        assertThat(resolution.effectiveLimit()).isEqualTo(10);
        assertThat(resolution.wouldConstrain()).isTrue();
    }

    @Test
    void shadowReportsSkillDecisionWithoutChangingRequestLimit() {
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setAgentConstraints(RolloutMode.SHADOW);
        AgentStepPolicy policy = new AgentStepPolicy(rollout, new SimpleMeterRegistry());

        AgentStepPolicy.Resolution resolution = policy.resolve(40, 10);

        assertThat(resolution.effectiveLimit()).isEqualTo(40);
        assertThat(resolution.enforcedLimit()).isEqualTo(10);
        assertThat(resolution.wouldConstrain()).isTrue();
    }

    @Test
    void platformHardLimitAlwaysAppliesEvenWhenRolloutIsOff() {
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setAgentConstraints(RolloutMode.OFF);
        AgentStepPolicy policy = new AgentStepPolicy(rollout, new SimpleMeterRegistry());

        assertThat(policy.resolve(100, 100).effectiveLimit()).isEqualTo(50);
    }
}
