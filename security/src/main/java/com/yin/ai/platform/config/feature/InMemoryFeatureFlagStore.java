package com.yin.ai.platform.config.feature;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存 Feature Flag 存储 — 单节点 fallback，无 Redis 时使用。
 */
public class InMemoryFeatureFlagStore implements FeatureFlagStore {

    private final ConcurrentHashMap<String, Boolean> flags = new ConcurrentHashMap<>();

    @Override
    public boolean isEnabled(String key) {
        return flags.getOrDefault(key, true);
    }

    @Override
    public void setEnabled(String key, boolean enabled) {
        flags.put(key, enabled);
    }

    @Override
    public Map<String, Boolean> listAll() {
        return Map.copyOf(flags);
    }

    @Override
    public Set<String> getDisabledFlags() {
        return flags.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
