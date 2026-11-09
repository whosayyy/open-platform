package com.yin.ai.platform.config.feature;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Feature Flag 存储 — 多节点共享，支持 Pub/Sub 推送变更通知。
 */
public class RedisFeatureFlagStore implements FeatureFlagStore {

    private static final Logger log = LoggerFactory.getLogger(RedisFeatureFlagStore.class);
    private static final String REDIS_KEY = "ai:feature-flags";

    private final RedissonClient redisson;

    public RedisFeatureFlagStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public boolean isEnabled(String key) {
        Object value = redisson.getMap(REDIS_KEY).get(key);
        if (value == null) return true; // 未配置默认启用
        return Boolean.parseBoolean(value.toString());
    }

    @Override
    public void setEnabled(String key, boolean enabled) {
        redisson.getMap(REDIS_KEY).put(key, String.valueOf(enabled));
        log.info("[FEATURE-FLAG] {} = {}", key, enabled);
    }

    @Override
    public Map<String, Boolean> listAll() {
        Map<String, Boolean> result = new HashMap<>();
        redisson.getMap(REDIS_KEY).forEach((k, v) ->
                result.put(k.toString(), Boolean.parseBoolean(v.toString())));
        return result;
    }

    @Override
    public Set<String> getDisabledFlags() {
        return listAll().entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
