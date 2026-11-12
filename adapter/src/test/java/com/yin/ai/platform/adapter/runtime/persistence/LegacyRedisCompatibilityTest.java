package com.yin.ai.platform.adapter.runtime.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyRedisCompatibilityTest {

    @Test
    void oldRedisTypesRemainDeprecatedCompatibilityShims() {
        assertCompatibilityShim(com.yin.ai.platform.agent.task.DefaultTaskEngine.class,
                RedisTaskEngine.class);
        assertCompatibilityShim(com.yin.ai.platform.agent.task.RedisContextStore.class,
                RedisContextStore.class);
        assertCompatibilityShim(com.yin.ai.platform.agent.task.RedisTaskTokenAccountant.class,
                RedisTaskTokenAccountant.class);
        assertCompatibilityShim(com.yin.ai.platform.agent.hitl.RedisHitlStore.class,
                RedisHitlStore.class);
    }

    private static void assertCompatibilityShim(Class<?> legacyType, Class<?> adapterType) {
        assertThat(legacyType.isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(adapterType.isAssignableFrom(legacyType)).isTrue();
    }
}
