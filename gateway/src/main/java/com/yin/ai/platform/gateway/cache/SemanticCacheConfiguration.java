package com.yin.ai.platform.gateway.cache;

import com.yin.ai.platform.gateway.GatewayProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语义缓存模块 Spring 配置。
 * <p>
 * 仅在 {@code ai.gateway.semantic-cache.enabled=true} 时激活。
 * {@link SemanticCacheStore} SPI 允许切换存储后端（Redis / ES）。
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.gateway.semantic-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SemanticCacheConfiguration {

    @Bean
    RedisSemanticCacheStore semanticCacheStore(RedissonClient redisson) {
        return new RedisSemanticCacheStore(redisson);
    }

    @Bean
    SemanticCacheManager semanticCacheManager(SemanticCacheStore store,
                                               EmbeddingModel embeddingModel,
                                               GatewayProperties gatewayProperties) {
        return new SemanticCacheManager(store, embeddingModel,
                gatewayProperties.getSemanticCache());
    }

    @Bean
    CacheFilter cacheFilter(SemanticCacheManager cacheManager) {
        return new CacheFilter(cacheManager);
    }
}
