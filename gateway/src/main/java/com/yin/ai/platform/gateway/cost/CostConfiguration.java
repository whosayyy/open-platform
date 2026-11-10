package com.yin.ai.platform.gateway.cost;

import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.config.CostConfig;
import com.yin.ai.platform.gateway.filter.CostFilter;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CostConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.gateway.cost-tracking-enabled", havingValue = "true", matchIfMissing = true)
    public TokenCostCalculator tokenCostCalculator(GatewayProperties properties) {
        return new TokenCostCalculator(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.gateway.cost-tracking-enabled", havingValue = "true", matchIfMissing = true)
    public CostLimiter costLimiter(GatewayProperties properties, ObjectProvider<RedissonClient> redissonProvider) {
        CostConfig config = properties.getCost() != null
                ? properties.getCost()
                : new CostConfig();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        return new CostLimiter(
                config.getPerRequestBudgetUsd(),
                config.getPerUserDailyBudgetUsd(),
                config.getPerUserMonthlyBudgetUsd(),
                redisson);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.gateway.cost-tracking-enabled", havingValue = "true", matchIfMissing = true)
    public CostFilter costFilter(TokenCostCalculator calculator, CostLimiter limiter,
                                  ModelRegistry modelRegistry,
                                  ObjectProvider<CostUsageRecorder> usageRecorder) {
        return new CostFilter(calculator, limiter, modelRegistry, usageRecorder.getIfAvailable());
    }
}
