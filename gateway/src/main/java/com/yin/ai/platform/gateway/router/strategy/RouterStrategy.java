package com.yin.ai.platform.gateway.router.strategy;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;

import java.util.List;

/**
 * 模型路由策略 SPI。
 * <p>
 * 决定从候选模型列表中选取哪个模型来处理当前请求。
 * 常见策略：最低成本、最低延迟、优先级 fallback、加权轮询等。
 */
public interface RouterStrategy {

    /** 策略名称（用于日志和配置） */
    String name();

    /**
     * 从候选模型中选取最合适的模型。
     *
     * @param request    网关请求（可用于按 caller/skill 分流）
     * @param candidates 已启用的候选模型列表
     * @param ctx        路由上下文（成本和延迟信息）
     * @return 选中的模型定义
     * @throws IllegalStateException 如果没有可用模型
     */
    ModelDefinition select(GatewayRequest request, List<ModelDefinition> candidates, RouterContext ctx);
}
