package com.yin.ai.platform.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.adapter.runtime.event.RedisAgentEventPublisher;
import com.yin.ai.platform.adapter.spring.web.AgentSseController;
import com.yin.ai.platform.agent.event.EventPublisher;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.ServerSentEvent;

/** Registers the Redis event journal and Spring SSE transport adapters. */
@AutoConfiguration
@ConditionalOnClass({RedissonClient.class, ServerSentEvent.class})
@ConditionalOnBean(RedissonClient.class)
@ConditionalOnProperty(prefix = "ai.platform", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiPlatformAgentEventAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public RedisAgentEventPublisher redisAgentEventPublisher(
            RedissonClient redisson, ObjectMapper objectMapper) {
        return new RedisAgentEventPublisher(redisson, objectMapper);
    }

    @Bean
    @ConditionalOnBean(RedisAgentEventPublisher.class)
    @ConditionalOnMissingBean(AgentSseController.class)
    public AgentSseController agentSseController(
            RedisAgentEventPublisher eventPublisher, ObjectMapper objectMapper) {
        return new AgentSseController(eventPublisher, objectMapper);
    }
}
