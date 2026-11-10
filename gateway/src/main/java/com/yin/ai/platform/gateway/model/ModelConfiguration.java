package com.yin.ai.platform.gateway.model;

import com.yin.ai.platform.gateway.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型注册中心 Spring 配置。
 * <p>
 * 从 {@link GatewayProperties#getModels()} 初始化 ModelDefinition 列表，
 * 创建 DefaultModelRegistry Bean。
 */
@Slf4j
@Configuration
public class ModelConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry(GatewayProperties gatewayProperties) {
        List<ModelDefinition> definitions = new ArrayList<>();

        if (gatewayProperties.getModels() != null) {
            gatewayProperties.getModels().forEach((name, config) -> {
                ModelDefinition def = ModelDefinition.builder()
                        .name(name)
                        .provider(config.getProvider())
                        .baseUrl(config.getBaseUrl() != null ? config.getBaseUrl() : "")
                        .apiKey(config.getApiKey() != null ? config.getApiKey() : "")
                        .maxTokens(config.getMaxTokens())
                        .costPer1kPromptTokens(config.getCostPer1kPromptTokens())
                        .costPer1kCompletionTokens(config.getCostPer1kCompletionTokens())
                        .enabled(config.isEnabled())
                        .rateLimitPerMinute(config.getRateLimitPerMinute())
                        .build();
                definitions.add(def);
            });
        }

        // 如果配置中没有任何模型定义，创建默认的 deepseek-chat
        if (definitions.isEmpty()) {
            log.warn("[MODEL] no models configured, creating default 'deepseek-chat'");
            definitions.add(ModelDefinition.builder()
                    .name("deepseek-chat")
                    .provider("deepseek")
                    .baseUrl(gatewayProperties.getModels() != null
                            && gatewayProperties.getModels().containsKey("deepseek-chat")
                            ? gatewayProperties.getModels().get("deepseek-chat").getBaseUrl() : "")
                    .maxTokens(gatewayProperties.getDefaultMaxTokens())
                    .enabled(true)
                    .build());
        }

        return new DefaultModelRegistry(definitions, gatewayProperties.getDefaultModel());
    }
}
