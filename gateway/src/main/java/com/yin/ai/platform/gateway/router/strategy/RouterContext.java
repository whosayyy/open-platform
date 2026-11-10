package com.yin.ai.platform.gateway.router.strategy;

import com.yin.ai.platform.gateway.model.ModelDefinition;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由上下文 — 传递给 RouterStrategy 的运行时数据，支持分布式（Redis）和本地两种模式。
 * <p>
 * 当注入 {@link RedissonClient} 时使用 Redis Hash 存储，多实例共享数据；
 * 否则回退到本地 ConcurrentHashMap（适用于单实例或测试）。
 */
public class RouterContext {

    private static final String KEY_LATENCY = "ai:router:latency";
    private static final String KEY_ERROR_RATE = "ai:router:error-rate";
    private static final String KEY_HEALTH = "ai:router:health";

    private final RedissonClient redisson;
    private final Map<String, Long> localLatency;
    private final Map<String, Double> localErrorRate;
    private final Map<String, Boolean> localHealth;

    public RouterContext() {
        this.redisson = null;
        this.localLatency = new ConcurrentHashMap<>();
        this.localErrorRate = new ConcurrentHashMap<>();
        this.localHealth = new ConcurrentHashMap<>();
    }

    public RouterContext(RedissonClient redisson) {
        this.redisson = redisson;
        this.localLatency = null;
        this.localErrorRate = null;
        this.localHealth = null;
    }

    /** 仅用于测试：注入可控的本地 Map */
    RouterContext(Map<String, Long> latency, Map<String, Double> errorRate, Map<String, Boolean> health) {
        this.redisson = null;
        this.localLatency = new ConcurrentHashMap<>(latency);
        this.localErrorRate = new ConcurrentHashMap<>(errorRate);
        this.localHealth = new ConcurrentHashMap<>(health);
    }

    public long getAvgLatencyMs(String modelName) {
        if (redisson != null) {
            Long v = latencyMap().get(modelName);
            return v != null ? v : 0L;
        }
        return localLatency.getOrDefault(modelName, 0L);
    }

    public double getErrorRate(String modelName) {
        if (redisson != null) {
            Double v = errorRateMap().get(modelName);
            return v != null ? v : 0.0;
        }
        return localErrorRate.getOrDefault(modelName, 0.0);
    }

    public boolean isHealthy(String modelName) {
        if (redisson != null) {
            Boolean v = healthMap().get(modelName);
            return v != null ? v : true;
        }
        return localHealth.getOrDefault(modelName, true);
    }

    public void recordLatency(String modelName, long latencyMs) {
        if (redisson != null) {
            latencyMap().put(modelName, latencyMs);
        } else {
            localLatency.merge(modelName, latencyMs, (old, val) -> (old + val) / 2);
        }
    }

    public void recordError(String modelName) {
        if (redisson != null) {
            RMap<String, Double> map = errorRateMap();
            Double current = map.get(modelName);
            double next = current != null ? Math.min(1.0, current * 0.7 + 0.3) : 0.3;
            map.fastPut(modelName, next);
        } else {
            localErrorRate.merge(modelName, 1.0, (old, val) -> Math.min(1.0, old * 0.7 + val * 0.3));
        }
    }

    public void recordSuccess(String modelName) {
        if (redisson != null) {
            RMap<String, Double> map = errorRateMap();
            Double current = map.get(modelName);
            double next = current != null ? current * 0.7 : 0.0;
            map.fastPut(modelName, next);
        } else {
            localErrorRate.merge(modelName, 0.0, (old, val) -> old * 0.7);
        }
    }

    public void setHealthStatus(String modelName, boolean healthy) {
        if (redisson != null) {
            healthMap().fastPut(modelName, healthy);
        } else {
            localHealth.put(modelName, healthy);
        }
    }

    public Map<String, Long> avgLatencyMs() {
        if (redisson != null) return latencyMap();
        return localLatency;
    }

    public Map<String, Double> errorRate() {
        if (redisson != null) return errorRateMap();
        return localErrorRate;
    }

    public Map<String, Boolean> healthStatus() {
        if (redisson != null) return healthMap();
        return localHealth;
    }

    public double getCostScore(ModelDefinition def) {
        return def.getCostPer1kPromptTokens() + def.getCostPer1kCompletionTokens();
    }

    private RMap<String, Long> latencyMap() {
        return redisson.getMap(KEY_LATENCY, JsonJacksonCodec.INSTANCE);
    }

    private RMap<String, Double> errorRateMap() {
        return redisson.getMap(KEY_ERROR_RATE, JsonJacksonCodec.INSTANCE);
    }

    private RMap<String, Boolean> healthMap() {
        return redisson.getMap(KEY_HEALTH, JsonJacksonCodec.INSTANCE);
    }
}
