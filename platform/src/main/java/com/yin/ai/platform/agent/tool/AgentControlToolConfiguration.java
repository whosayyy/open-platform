package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Control Tools 自动配置 — 注册 6 个 Function Bean。
 * LLM 通过 Spring AI Tool Calling 调用这些 Function。
 */
@Configuration
@ConditionalOnBean(TaskEngine.class)
public class AgentControlToolConfiguration {

    @Bean
    public CreateTaskPlanFunction createTaskPlanFunction(ContextStore contextStore) {
        return new CreateTaskPlanFunction(contextStore);
    }

    @Bean
    public UpdateStepStatusFunction updateStepStatusFunction(TaskEngine taskEngine,
                                                              EventPublisher eventPublisher) {
        return new UpdateStepStatusFunction(taskEngine, eventPublisher);
    }

    @Bean
    public GetNextStepFunction getNextStepFunction(TaskEngine taskEngine) {
        return new GetNextStepFunction(taskEngine);
    }

    @Bean
    public SaveContextFunction saveContextFunction(ContextStore contextStore) {
        return new SaveContextFunction(contextStore);
    }

    @Bean
    public GetContextFunction getContextFunction(TaskEngine taskEngine) {
        return new GetContextFunction(taskEngine);
    }

    @Bean
    public AppendArtifactFunction appendArtifactFunction(ContextStore contextStore,
                                                          EventPublisher eventPublisher) {
        return new AppendArtifactFunction(contextStore, eventPublisher);
    }
}
