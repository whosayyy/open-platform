package com.yin.ai.platform.rollout;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Central rollout switches for capabilities delivered by the roadmap closure.
 * Security-sensitive policies start in shadow mode and can be enforced
 * independently after their metrics have been observed.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.roadmap")
public class RoadmapFeatureProperties {

    private RolloutMode gatewayPolicy = RolloutMode.SHADOW;
    private RolloutMode tenantIsolation = RolloutMode.SHADOW;
    private RolloutMode legacyDelegation = RolloutMode.OFF;
    private RolloutMode nacosSkillStore = RolloutMode.OFF;
    private RolloutMode promptExperiment = RolloutMode.OFF;
    private RolloutMode ragAsyncIndex = RolloutMode.OFF;
    private RolloutMode mcp = RolloutMode.OFF;
    private RolloutMode agentConstraints = RolloutMode.SHADOW;
}
