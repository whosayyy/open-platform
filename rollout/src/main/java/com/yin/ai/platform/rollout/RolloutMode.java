package com.yin.ai.platform.rollout;

/**
 * Controls how a roadmap capability is introduced without requiring a redeploy
 * of the calling code.
 */
public enum RolloutMode {
    OFF,
    SHADOW,
    ENFORCE;

    public boolean isActive() {
        return this != OFF;
    }

    public boolean isEnforcing() {
        return this == ENFORCE;
    }
}
