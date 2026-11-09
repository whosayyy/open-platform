package com.yin.ai.platform.adapter.router.cache;

/** Infrastructure options for the Caffeine/Redis intent cache adapter. */
public record IntentCacheOptions(boolean enabled, int maxSize, int ttlSeconds) {

    public IntentCacheOptions {
        maxSize = maxSize > 0 ? maxSize : 10_000;
        ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 1_800;
    }
}
