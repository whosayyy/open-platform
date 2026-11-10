package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class SemanticCacheConfig {
    /** 是否启用语义缓存 */
    private boolean enabled = true;
    /** 余弦相似度阈值 (0.0 ~ 1.0)，高于此值视为缓存命中 */
    private double similarityThreshold = 0.85;
    /** 缓存条目 TTL（秒） */
    private long ttlSeconds = 3600;
    /** 最大缓存条目数 */
    private int maxEntries = 1000;
}
