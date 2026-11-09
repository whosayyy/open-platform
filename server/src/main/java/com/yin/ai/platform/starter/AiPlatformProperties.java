package com.yin.ai.platform.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/** Canonical root properties for platform-wide switches. */
@ConfigurationProperties("ai.platform")
public class AiPlatformProperties {

    private boolean enabled = true;
    private CoreDelegation coreDelegation = new CoreDelegation();
    private AgentStateStore agentStateStore = new AgentStateStore();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CoreDelegation getCoreDelegation() {
        return coreDelegation;
    }

    public void setCoreDelegation(CoreDelegation coreDelegation) {
        this.coreDelegation = coreDelegation == null ? new CoreDelegation() : coreDelegation;
    }

    public AgentStateStore getAgentStateStore() {
        return agentStateStore;
    }

    public void setAgentStateStore(AgentStateStore agentStateStore) {
        this.agentStateStore = agentStateStore == null ? new AgentStateStore() : agentStateStore;
    }

    /** Infrastructure selection for run records and resumable pattern checkpoints. */
    public static class AgentStateStore {
        /** Memory remains the compatibility default; Redis is an explicit opt-in. */
        private String type = "memory";
        private String redisKeyPrefix = "ai:core:agent:";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getRedisKeyPrefix() { return redisKeyPrefix; }
        public void setRedisKeyPrefix(String redisKeyPrefix) {
            this.redisKeyPrefix = redisKeyPrefix;
        }
    }

    /** Rollout controls for executing legacy tasks with pure core patterns. */
    public static class CoreDelegation {
        /** Disabled by default until a deployment explicitly opens the rollout. */
        private boolean enabled;
        /** DIRECT is the first low-risk migration cohort when rollout is enabled. */
        private Set<String> modes = new LinkedHashSet<>(Set.of("DIRECT"));
        private String requestOverrideKey = "coreDelegation";
        private int maxReactIterations = 8;
        private int maxPlanRounds = 32;
        private int maxStepRetries = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Set<String> getModes() { return modes; }
        public void setModes(Set<String> modes) {
            this.modes = modes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(modes);
        }
        public String getRequestOverrideKey() { return requestOverrideKey; }
        public void setRequestOverrideKey(String requestOverrideKey) {
            this.requestOverrideKey = requestOverrideKey;
        }
        public int getMaxReactIterations() { return maxReactIterations; }
        public void setMaxReactIterations(int maxReactIterations) {
            this.maxReactIterations = maxReactIterations;
        }
        public int getMaxPlanRounds() { return maxPlanRounds; }
        public void setMaxPlanRounds(int maxPlanRounds) { this.maxPlanRounds = maxPlanRounds; }
        public int getMaxStepRetries() { return maxStepRetries; }
        public void setMaxStepRetries(int maxStepRetries) { this.maxStepRetries = maxStepRetries; }
    }
}
