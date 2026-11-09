package com.yin.ai.platform.gateway.cost;

import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.model.ModelDefinition;

/**
 * Token 成本计算器 — 根据模型定价和 TokenUsage 计算精确成本。
 * <p>
 * 缓存计费逻辑（DeepSeek 模型）：
 * <ul>
 *   <li>命中缓存：cached prompt tokens 仅按 10% 计费</li>
 *   <li>写入缓存：cache write tokens 按 110% 计费（100% 基础 + 10% 写入）</li>
 *   <li>未命中且未写入：按标准定价 100% 计费</li>
 * </ul>
 */
public class TokenCostCalculator {

    private final GatewayProperties properties;

    public TokenCostCalculator(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据模型定义和 Token 用量计算详细成本。
     */
    public CostResult calculate(ModelDefinition def, TokenUsage usage) {
        if (usage == null) {
            return CostResult.builder().model(def.getName()).build();
        }

        double cacheRatio = properties.getPromptCache() != null
                ? properties.getPromptCache().getCacheHitCostRatio() : 0.1;

        int rawPrompt = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completion = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        int cached = usage.getCachedPromptTokens() != null ? usage.getCachedPromptTokens() : 0;
        int cacheWrite = usage.getCacheWriteTokens() != null ? usage.getCacheWriteTokens() : 0;

        int nonCachedNonWrite = rawPrompt - cached - cacheWrite;
        int billablePrompt = nonCachedNonWrite + (int) Math.ceil(cached * cacheRatio)
                + (int) Math.ceil(cacheWrite * (1.0 + cacheRatio));

        int billableCompletion = completion;

        double promptCost = (billablePrompt / 1000.0) * def.getCostPer1kPromptTokens();
        double completionCost = (billableCompletion / 1000.0) * def.getCostPer1kCompletionTokens();

        double fullCost = (rawPrompt / 1000.0) * def.getCostPer1kPromptTokens()
                + (completion / 1000.0) * def.getCostPer1kCompletionTokens();
        double cacheDiscount = cached > 0 || cacheWrite > 0
                ? fullCost - (promptCost + completionCost)
                : 0;

        boolean cacheHit = usage.isCacheHit() || cached > 0;

        return CostResult.builder()
                .model(def.getName())
                .billablePromptTokens(billablePrompt)
                .billableCompletionTokens(billableCompletion)
                .promptCost(promptCost)
                .completionCost(completionCost)
                .cacheDiscount(cacheDiscount)
                .totalCost(promptCost + completionCost)
                .cacheHit(cacheHit)
                .build();
    }

    /**
     * 快速预估（无 TokenUsage 时用）。
     */
    public double estimate(ModelDefinition def, int promptTokens, int completionTokens) {
        return def.estimateCost(promptTokens, completionTokens);
    }
}
