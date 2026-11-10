package com.yin.ai.platform.gateway.router.strategy;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 优先级 fallback 策略 — 按预定义优先级列表顺序选择第一个可用的模型。
 * <p>
 * 适用场景：主模型（如 deepseek-chat）+ 备用模型（如 qwen-plus），
 * 主模型故障时自动切换到备用模型。
 */
public class PriorityFallbackStrategy implements RouterStrategy {

    private final List<String> priorityOrder;

    /**
     * @param priorityOrder 模型名称优先级列表，越靠前越优先
     */
    public PriorityFallbackStrategy(List<String> priorityOrder) {
        this.priorityOrder = new ArrayList<>(priorityOrder);
    }

    @Override
    public String name() {
        return "priority-fallback";
    }

    @Override
    public ModelDefinition select(GatewayRequest request, List<ModelDefinition> candidates, RouterContext ctx) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available models for routing");
        }

        // 按优先级顺序查找第一个健康且存在的候选模型
        for (String modelName : priorityOrder) {
            for (ModelDefinition def : candidates) {
                if (def.getName().equals(modelName) && ctx.isHealthy(modelName)) {
                    return def;
                }
            }
        }

        // 如果优先级列表中的模型都不可用，回退到任意健康模型
        for (ModelDefinition def : candidates) {
            if (ctx.isHealthy(def.getName())) {
                return def;
            }
        }

        // 所有模型都不健康时，返回优先级列表中的第一个
        return candidates.get(0);
    }

    public List<String> getPriorityOrder() {
        return new ArrayList<>(priorityOrder);
    }
}
