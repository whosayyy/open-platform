package com.yin.ai.platform.gateway.router.strategy;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 最低成本策略 — 从已启用且健康的候选模型中选取 Token 单价最低的模型。
 * <p>
 * 成本分数 = costPer1kPromptTokens + costPer1kCompletionTokens（越低越好）。
 * 如果所有模型成本相同，按名称字典序稳定排序。
 */
public class LowestCostStrategy implements RouterStrategy {

    @Override
    public String name() {
        return "lowest-cost";
    }

    @Override
    public ModelDefinition select(GatewayRequest request, List<ModelDefinition> candidates, RouterContext ctx) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available models for routing");
        }

        List<ModelDefinition> healthy = candidates.stream()
                .filter(c -> ctx.isHealthy(c.getName()))
                .collect(Collectors.toList());

        List<ModelDefinition> pool = healthy.isEmpty() ? candidates : healthy;

        return pool.stream()
                .min(Comparator.comparingDouble(ctx::getCostScore)
                        .thenComparing(ModelDefinition::getName))
                .orElseThrow(() -> new IllegalStateException("No model found after filtering"));
    }
}
