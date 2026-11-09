package com.yin.ai.platform.gateway.router;

import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.router.strategy.LowestCostStrategy;
import com.yin.ai.platform.gateway.router.strategy.PriorityFallbackStrategy;
import com.yin.ai.platform.gateway.router.strategy.RouterContext;
import com.yin.ai.platform.gateway.router.strategy.RouterStrategy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class ModelRouterConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RouterContext routerContext(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        return redisson != null ? new RouterContext(redisson) : new RouterContext();
    }

    @Bean
    @ConditionalOnMissingBean
    public RouterStrategy routerStrategy(GatewayProperties gatewayProperties) {
        var routerProps = gatewayProperties.getModelRouter();
        String strategyName = routerProps != null ? routerProps.getStrategy() : "lowest-cost";

        return switch (strategyName) {
            case "lowest-cost" -> new LowestCostStrategy();
            case "priority-fallback" -> {
                List<String> priority = routerProps.getPriorityOrder();
                if (priority == null || priority.isEmpty()) {
                    log.warn("[ROUTER] priority-fallback strategy selected but priorityOrder is empty");
                }
                yield new PriorityFallbackStrategy(priority != null ? priority : List.of());
            }
            default -> {
                log.warn("[ROUTER] unknown strategy '{}', falling back to lowest-cost", strategyName);
                yield new LowestCostStrategy();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRouter modelRouter(ModelRegistry modelRegistry, RouterStrategy routerStrategy,
                                    RouterContext routerContext) {
        return new DefaultModelRouter(modelRegistry, routerStrategy, routerContext);
    }
}
