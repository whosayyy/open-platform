package com.yin.ai.platform.config.feature;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Feature Flag 自动配置。
 */
@Configuration
@EnableAspectJAutoProxy
public class FeatureFlagConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeatureFlagStore featureFlagStore(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson != null) {
            return new RedisFeatureFlagStore(redisson);
        }
        return new InMemoryFeatureFlagStore();
    }

    @Bean
    public FeatureGateAspect featureGateAspect(FeatureFlagStore flagStore) {
        return new FeatureGateAspect(flagStore);
    }
}
