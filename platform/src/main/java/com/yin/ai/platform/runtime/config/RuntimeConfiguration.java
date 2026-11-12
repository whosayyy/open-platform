package com.yin.ai.platform.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.engine.TaskManager;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.knowledge.KnowledgeRegistry;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.engine.metrics.AiMetrics;
import com.yin.ai.platform.runtime.DefaultSkillRuntime;
import com.yin.ai.platform.runtime.ResponseParser;
import com.yin.ai.platform.runtime.SkillRuntime;
import com.yin.ai.platform.runtime.postprocess.ResponsePostProcessorChain;
import com.yin.ai.platform.runtime.PromptComposer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill Runtime 模块 Spring 配置。
 */
@Configuration
public class RuntimeConfiguration {

    @Bean
    public ResponseParser responseParser(ObjectMapper objectMapper) {
        return new ResponseParser(objectMapper);
    }

    @Bean
    public SkillRuntime skillRuntime(
            SkillLoader skillLoader,
            UnifiedChatClient gateway,
            FunctionRegistry functionRegistry,
            SessionMemoryManager sessionMemoryManager,
            ObjectMapper objectMapper,
            PromptComposer promptComposer,
            @Autowired(required = false) KnowledgeRegistry knowledgeRegistry,
            ResponsePostProcessorChain postProcessorChain,
            AiMetrics aiMetrics,
            GatewayProperties gatewayProperties,
            SinkStreamProducer streamProducer,
            TaskManager taskManager) {
        return new DefaultSkillRuntime(
                skillLoader, gateway, functionRegistry, sessionMemoryManager, objectMapper,
                promptComposer, knowledgeRegistry, postProcessorChain, aiMetrics,
                gatewayProperties, streamProducer, taskManager);
    }
}
