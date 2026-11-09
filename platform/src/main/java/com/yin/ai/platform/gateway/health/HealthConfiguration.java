package com.yin.ai.platform.gateway.health;

import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.config.HealthConfig;
import com.yin.ai.platform.gateway.filter.HealthFilter;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.router.strategy.RouterContext;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class HealthConfiguration implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.gateway.health.failure-threshold", matchIfMissing = true)
    public ModelHealthManager modelHealthManager(ModelRegistry registry, RouterContext routerContext,
                                                  GatewayProperties properties,
                                                  ObjectProvider<RedissonClient> redissonProvider) {
        HealthConfig config = properties.getHealth();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        return new ModelHealthManager(
                registry,
                routerContext,
                config.getFailureThreshold(),
                config.getRecoveryWaitSeconds() * 1000,
                redisson);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.gateway.health.failure-threshold", matchIfMissing = true)
    public HealthFilter healthFilter(ModelHealthManager healthManager) {
        return new HealthFilter(healthManager);
    }

    @Scheduled(fixedDelayString = "${ai.gateway.health.health-check-interval-seconds:15}000")
    public void scheduledHealthCheck() {
        applicationContext.getBean(ModelHealthManager.class).runHealthCheck();
    }
}
