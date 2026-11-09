package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class RateLimitConfig {
    /** 是否启用限流 */
    private boolean enabled = false;
    /** 每个用户每分钟最大请求数 */
    private int maxRequestsPerMinute = 60;
    /** 限流窗口大小（秒） */
    private int windowSeconds = 60;
    /** 限流键前缀 */
    private String keyPrefix = "ai:ratelimit:";
}
