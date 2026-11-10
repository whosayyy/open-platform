package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class PromptCacheConfig {
    /** 是否启用 Prompt Caching（通过 cache_control 标记） */
    private boolean enabled = true;
    /** 缓存命中的 prompt tokens 计费比例（DeepSeek: 0.1 = 10%） */
    private double cacheHitCostRatio = 0.1;
}
