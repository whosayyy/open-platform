package com.yin.ai.platform.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yin.ai.platform.adapter.runtime.event.RedisAgentEventPublisher;
import com.yin.ai.platform.adapter.spring.web.AgentSseController;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiPlatformAgentEventAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiPlatformAgentEventAutoConfiguration.class))
            .withUserConfiguration(EventInfrastructure.class);

    @Test
    void registersRedisJournalAndSseAdapter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context).hasSingleBean(RedisAgentEventPublisher.class);
            assertThat(context).hasSingleBean(AgentSseController.class);
        });
    }

    @Test
    void backsOffForACustomEventPublisher() {
        runner.withUserConfiguration(CustomPublisherConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context).doesNotHaveBean(RedisAgentEventPublisher.class);
            assertThat(context).doesNotHaveBean(AgentSseController.class);
        });
    }

    @Test
    void canDisableEventAdaptersWithThePlatformSwitch() {
        runner.withPropertyValues("ai.platform.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RedisAgentEventPublisher.class);
            assertThat(context).doesNotHaveBean(AgentSseController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class EventInfrastructure {
        @Bean
        RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPublisherConfiguration {
        @Bean
        EventPublisher customEventPublisher() {
            return new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                }

                @Override
                public Flux<AgentEvent> replay(String taskId, String lastEventId) {
                    return Flux.empty();
                }
            };
        }
    }
}
