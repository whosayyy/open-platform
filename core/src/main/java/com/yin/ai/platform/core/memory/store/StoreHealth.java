package com.yin.ai.platform.core.memory.store;

/**
 * 存储健康状态 — 用于 MemoryStore 的 health check。
 * latencyMs 在 healthy 时记录延迟，unhealthy 时为 -1。
 */
public record StoreHealth(boolean healthy, String message, long latencyMs) {

    public static StoreHealth healthy(long latencyMs) {
        return new StoreHealth(true, "OK", latencyMs);
    }

    public static StoreHealth unhealthy(String message) {
        return new StoreHealth(false, message, -1);
    }
}
