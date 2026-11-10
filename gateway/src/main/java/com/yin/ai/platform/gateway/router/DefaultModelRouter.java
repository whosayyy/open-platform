package com.yin.ai.platform.gateway.router;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.router.strategy.RouterContext;
import com.yin.ai.platform.gateway.router.strategy.RouterStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 默认模型路由器实现。
 */
@Slf4j
public class DefaultModelRouter implements ModelRouter {

    private final ModelRegistry modelRegistry;
    private final RouterContext routerContext;
    private volatile RouterStrategy strategy;

    public DefaultModelRouter(ModelRegistry modelRegistry, RouterStrategy initialStrategy,
                               RouterContext routerContext) {
        this.modelRegistry = modelRegistry;
        this.routerContext = routerContext;
        this.strategy = initialStrategy;
        log.info("[ROUTER] initialized with strategy='{}', candidates={}",
                initialStrategy.name(), modelRegistry.listEnabled().size());
    }

    @Override
    public ModelDefinition route(GatewayRequest request) {
        // 显式指定的模型优先
        String explicitModel = request.getOptions() != null ? request.getOptions().getModel() : null;
        if (explicitModel != null && !explicitModel.isBlank()) {
            ModelDefinition def = modelRegistry.get(explicitModel)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Explicitly requested model not found: " + explicitModel));
            if (!def.isEnabled()) {
                log.warn("[ROUTER] requested model '{}' is disabled", explicitModel);
            }
            return def;
        }

        // 使用策略路由
        List<ModelDefinition> candidates = modelRegistry.listEnabled();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No enabled models available");
        }

        RouterStrategy currentStrategy = this.strategy;
        ModelDefinition selected = currentStrategy.select(request, candidates, routerContext);

        log.debug("[ROUTER] strategy='{}' selected='{}' from {} candidates",
                currentStrategy.name(), selected.getName(), candidates.size());
        return selected;
    }

    @Override
    public void setStrategy(RouterStrategy strategy) {
        log.info("[ROUTER] strategy changed: '{}' → '{}'",
                this.strategy.name(), strategy.name());
        this.strategy = strategy;
    }

    @Override
    public RouterStrategy getCurrentStrategy() {
        return strategy;
    }

    /**
     * 获取路由上下文（用于注入延迟/错误率等运行时数据）。
     */
    public RouterContext getRouterContext() {
        return routerContext;
    }
}
