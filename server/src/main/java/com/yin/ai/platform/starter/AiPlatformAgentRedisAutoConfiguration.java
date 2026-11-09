package com.yin.ai.platform.starter;

import com.yin.ai.platform.adapter.runtime.persistence.RedisAgentCheckpointStore;
import com.yin.ai.platform.adapter.runtime.persistence.RedisAgentRunStore;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import com.yin.ai.platform.core.agent.store.AgentRunStore;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Opt-in Redis persistence adapter registration for the core Agent Runtime. */
@AutoConfiguration(before = AiPlatformCoreAutoConfiguration.class)
@EnableConfigurationProperties(AiPlatformProperties.class)
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnBean(RedissonClient.class)
@ConditionalOnProperty(prefix = "ai.platform.agent-state-store", name = "type",
        havingValue = "redis")
@ConditionalOnProperty(prefix = "ai.platform", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiPlatformAgentRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentRunStore.class)
    public AgentRunStore redisAiPlatformAgentRunStore(
            RedissonClient redisson, AiPlatformProperties properties) {
        return new RedisAgentRunStore(redisson,
                properties.getAgentStateStore().getRedisKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(AgentCheckpointStore.class)
    public AgentCheckpointStore redisAiPlatformAgentCheckpointStore(
            RedissonClient redisson, AiPlatformProperties properties) {
        return new RedisAgentCheckpointStore(redisson,
                properties.getAgentStateStore().getRedisKeyPrefix());
    }
}
