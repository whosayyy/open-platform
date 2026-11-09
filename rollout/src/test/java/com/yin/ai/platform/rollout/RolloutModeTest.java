package com.yin.ai.platform.rollout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RolloutModeTest {

    @Test
    void exposesStableActivationSemantics() {
        assertThat(RolloutMode.OFF.isActive()).isFalse();
        assertThat(RolloutMode.OFF.isEnforcing()).isFalse();
        assertThat(RolloutMode.SHADOW.isActive()).isTrue();
        assertThat(RolloutMode.SHADOW.isEnforcing()).isFalse();
        assertThat(RolloutMode.ENFORCE.isActive()).isTrue();
        assertThat(RolloutMode.ENFORCE.isEnforcing()).isTrue();
    }
}
