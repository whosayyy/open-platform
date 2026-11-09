package com.yin.ai.platform.prompt.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.prompt.model.VariableDef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Prompt Center 缓存配置 — Caffeine L1 缓存。
 * <p>
 * Fragment 缓存：name@version → Handlebars 编译后模板
 * Variable 缓存：name → VariableDef
 */
@Configuration
public class PromptCacheConfig {

    @Bean
    public Cache<String, Object> fragmentTemplateCache(PromptCenterProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(props.getCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(200)
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, VariableDef> variableDefCache(PromptCenterProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(props.getCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(100)
                .recordStats()
                .build();
    }
}
