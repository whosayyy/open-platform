package com.yin.ai.platform.gateway.router;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.router.strategy.RouterStrategy;

/**
 * 模型路由器 — 根据配置的路由策略从模型池中选择最佳模型。
 * <p>
 * 每次 LLM 请求前调用，返回应使用的 ModelDefinition。
 * 如果 GatewayRequest 已显式指定模型，则直接解析该模型（跳过路由策略）。
 */
public interface ModelRouter {

    /**
     * 为请求选择最佳模型。
     * <p>
     * 如果 {@code request.options.model} 已指定模型名，则直接从 ModelRegistry 解析；
     * 否则使用当前路由策略从所有已启用模型中选取。
     *
     * @param request 网关请求
     * @return 选中的模型定义
     */
    ModelDefinition route(GatewayRequest request);

    /**
     * 动态切换路由策略。
     */
    void setStrategy(RouterStrategy strategy);

    /**
     * 获取当前路由策略。
     */
    RouterStrategy getCurrentStrategy();
}
