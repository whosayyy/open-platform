package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvictionPolicyValidationTest {
    @Test
    void keepsDocumentedDefaults() {
        assertThat(EvictionPolicy.defaults().highWatermark()).isEqualTo(10_000);
        assertThat(EvictionPolicy.defaults().lowWatermark()).isEqualTo(8_000);
    }

    @Test
    void enabledPolicyRejectsInvalidWatermarks() {
        assertThatThrownBy(() -> new EvictionPolicy(EvictionStrategy.FIFO,
                8_000, 8_000, MemoryType.EPISODIC, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvictionPolicy(EvictionStrategy.FIFO,
                8_000, -1, MemoryType.EPISODIC, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
