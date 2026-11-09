package com.yin.ai.platform.starter.router;

import com.yin.ai.platform.adapter.router.cache.IntentCacheOptions;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Intent 缓存配置属性。
 *
 * <pre>
 * ai:
 *   router:
 *     cache:
 *       enabled: true
 *       max-size: 10000
 *       ttl-seconds: 1800
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.router.cache")
public class IntentCacheProperties {

    /** 是否启用缓存（默认 true）。 */
    private boolean enabled = true;

    /** 最大缓存条目数。 */
    private int maxSize = 10000;

    /** 缓存 TTL（秒），默认 30 分钟。 */
    private int ttlSeconds = 1800;

    public IntentCacheOptions toOptions() {
        return new IntentCacheOptions(enabled, maxSize, ttlSeconds);
    }
}
