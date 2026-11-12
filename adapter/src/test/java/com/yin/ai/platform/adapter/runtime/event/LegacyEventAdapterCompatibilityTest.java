package com.yin.ai.platform.adapter.runtime.event;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyEventAdapterCompatibilityTest {

    @Test
    void legacyEventTypesRemainDeprecatedCompatibilityShims() {
        Class<?> legacyPublisher = com.yin.ai.platform.agent.event.DualWriteEventPublisher.class;
        Class<?> legacyController = com.yin.ai.platform.agent.event.AgentSseController.class;

        assertThat(legacyPublisher.isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(RedisAgentEventPublisher.class.isAssignableFrom(legacyPublisher)).isTrue();
        assertThat(legacyController.isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(com.yin.ai.platform.adapter.spring.web.AgentSseController.class
                .isAssignableFrom(legacyController)).isTrue();
        assertThat(legacyController.getDeclaredAnnotation(RestController.class)).isNull();
        assertThat(com.yin.ai.platform.adapter.spring.web.AgentSseController.class
                .getDeclaredAnnotation(RestController.class)).isNotNull();
    }
}
