package com.yin.ai.platform.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.workflow.runtime.RedisWorkflowTaskStore;
import com.yin.ai.platform.workflow.runtime.RedisStepIdempotencyStore;
import com.yin.ai.platform.workflow.runtime.StepIdempotencyStore;
import com.yin.ai.platform.workflow.runtime.WorkflowTaskStore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工作流运行时装配 — 任务存储默认走 Redis，Redisson 不可用时降级为进程内存储。
 */
@Configuration
public class WorkflowConfiguration {

    @Bean
    public WorkflowTaskStore workflowTaskStore(ObjectProvider<RedissonClient> redissonProvider,
                                               ObjectMapper objectMapper) {
        return new RedisWorkflowTaskStore(redissonProvider.getIfAvailable(), objectMapper);
    }

    @Bean
    public StepIdempotencyStore stepIdempotencyStore(ObjectProvider<RedissonClient> redissonProvider) {
        return new RedisStepIdempotencyStore(redissonProvider.getIfAvailable());
    }
}
