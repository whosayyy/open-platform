package com.yin.ai.platform.gateway.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 注册 CacheControlHttpInterceptor 到 RestClient，实现 DeepSeek Prompt Caching。
 * <p>
 * 拦截器仅在 {@link CacheControlContext} 标记为 true 时启用，
 * 对非 AI 请求零影响。
 */
@Slf4j
@Configuration
@ConditionalOnClass(OpenAiApi.class)
@ConditionalOnProperty(prefix = "ai.gateway.prompt-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheControlConfiguration {

    @Bean
    public RestClientCustomizer cacheControlRestClientCustomizer() {
        return (RestClient.Builder builder) -> {
            builder.requestInterceptor(new CacheControlHttpInterceptor());
            log.info("[CACHE] Registered CacheControlHttpInterceptor");
        };
    }
}
