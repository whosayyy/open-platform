package com.yin.ai.platform.gateway.health;

import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.router.strategy.RouterContext;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型健康管理器 — 维护每个模型的熔断器，与 RouterContext 双向同步。
 * <p>
 * 当注入 {@link RedissonClient} 时使用 Redis Hash 存储熔断器状态，多实例共享；
 * 否则回退到本地 ConcurrentHashMap。
 */
@Slf4j
public class ModelHealthManager {

    private static final String KEY_PREFIX = "ai:health:breaker:";

    private final ModelRegistry registry;
    private final RouterContext routerContext;
    private final int failureThreshold;
    private final long recoveryWaitMs;
    private final RedissonClient redisson;
    private final Map<String, CircuitBreakerState> localBreakers;

    public ModelHealthManager(ModelRegistry registry, RouterContext routerContext,
                               int failureThreshold, long recoveryWaitMs) {
        this(registry, routerContext, failureThreshold, recoveryWaitMs, null);
    }

    public ModelHealthManager(ModelRegistry registry, RouterContext routerContext,
                               int failureThreshold, long recoveryWaitMs, RedissonClient redisson) {
        this.registry = registry;
        this.routerContext = routerContext;
        this.failureThreshold = failureThreshold;
        this.recoveryWaitMs = recoveryWaitMs;
        this.redisson = redisson;
        this.localBreakers = redisson == null ? new ConcurrentHashMap<>() : null;
    }

    /** 记录模型调用成功 */
    public void recordSuccess(String modelName) {
        if (redisson != null) {
            recordSuccessRedis(modelName);
        } else {
            CircuitBreakerState breaker = localBreakerFor(modelName);
            breaker.recordSuccess();
        }
        routerContext.recordSuccess(modelName);
        routerContext.setHealthStatus(modelName, true);
    }

    /** 记录模型调用失败 — 可能触发熔断 */
    public void recordFailure(String modelName) {
        boolean tripped;
        if (redisson != null) {
            tripped = recordFailureRedis(modelName);
        } else {
            CircuitBreakerState breaker = localBreakerFor(modelName);
            tripped = breaker.recordFailure();
        }
        routerContext.recordError(modelName);

        if (redisson != null) {
            routerContext.setHealthStatus(modelName, isRedisHealthy(modelName));
        } else {
            CircuitBreakerState breaker = localBreakerFor(modelName);
            routerContext.setHealthStatus(modelName, breaker.allowsRequest());
        }

        if (tripped) {
            log.warn("[HEALTH] circuit OPEN for '{}' after failures", modelName);
        }
    }

    /** 执行健康检查（调度器定期调用） — 对 OPEN 状态的模型尝试 HALF_OPEN 过渡 */
    public void runHealthCheck() {
        for (ModelDefinition def : registry.listEnabled()) {
            String name = def.getName();

            if (redisson != null) {
                tryHalfOpenRedis(name);
            } else {
                CircuitBreakerState breaker = localBreakers.get(name);
                if (breaker == null) continue;
                if (breaker.getState() == CircuitBreakerState.State.OPEN) {
                    if (breaker.tryHalfOpen()) {
                        log.info("[HEALTH] circuit HALF_OPEN for '{}'", name);
                        routerContext.setHealthStatus(name, true);
                    }
                }
            }
        }
    }

    /** 获取模型熔断器状态 */
    public CircuitBreakerState getBreaker(String modelName) {
        if (redisson != null) {
            return getBreakerFromRedis(modelName);
        }
        return localBreakers.get(modelName);
    }

    /** 获取所有熔断器状态快照 */
    public Map<String, CircuitBreakerState.State> getBreakerStates() {
        Map<String, CircuitBreakerState.State> states = new ConcurrentHashMap<>();
        if (redisson != null) {
            for (ModelDefinition def : registry.listEnabled()) {
                CircuitBreakerState breaker = getBreakerFromRedis(def.getName());
                if (breaker != null) {
                    states.put(def.getName(), breaker.getState());
                }
            }
        } else {
            localBreakers.forEach((k, v) -> states.put(k, v.getState()));
        }
        return states;
    }

    /** 手动重置模型熔断器 */
    public void reset(String modelName) {
        if (redisson != null) {
            RMap<String, String> map = breakerMap(modelName);
            map.fastPut("state", "CLOSED");
            map.fastPut("failures", "0");
            map.fastPut("openedAt", "0");
        } else {
            CircuitBreakerState breaker = localBreakers.get(modelName);
            if (breaker != null) breaker.reset();
        }
        routerContext.setHealthStatus(modelName, true);
        log.info("[HEALTH] circuit manually reset for '{}'", modelName);
    }

    // -- Redis operations --

    private void recordSuccessRedis(String modelName) {
        RMap<String, String> map = breakerMap(modelName);
        map.fastPut("failures", "0");
        String state = map.get("state");
        if ("HALF_OPEN".equals(state)) {
            map.fastPut("state", "CLOSED");
        }
    }

    private boolean recordFailureRedis(String modelName) {
        RMap<String, String> map = breakerMap(modelName);
        long failures = incrementRedisFailures(map);
        String state = map.get("state");
        if (state == null) state = "CLOSED";

        if (("CLOSED".equals(state) && failures >= failureThreshold)
                || "HALF_OPEN".equals(state)) {
            map.fastPut("state", "OPEN");
            map.fastPut("openedAt", String.valueOf(System.currentTimeMillis()));
            return true;
        }
        return false;
    }

    private void tryHalfOpenRedis(String modelName) {
        RMap<String, String> map = breakerMap(modelName);
        String state = map.get("state");
        if (!"OPEN".equals(state)) return;

        String openedStr = map.get("openedAt");
        long openedAt = openedStr != null ? Long.parseLong(openedStr) : 0;
        if (System.currentTimeMillis() - openedAt >= recoveryWaitMs) {
            map.fastPut("state", "HALF_OPEN");
            routerContext.setHealthStatus(modelName, true);
            log.info("[HEALTH] circuit HALF_OPEN for '{}'", modelName);
        }
    }

    private boolean isRedisHealthy(String modelName) {
        RMap<String, String> map = breakerMap(modelName);
        String state = map.get("state");
        if (state == null || "CLOSED".equals(state) || "HALF_OPEN".equals(state)) return true;
        // OPEN: check if recovery time elapsed
        String openedStr = map.get("openedAt");
        long openedAt = openedStr != null ? Long.parseLong(openedStr) : 0;
        return System.currentTimeMillis() - openedAt >= recoveryWaitMs;
    }

    private CircuitBreakerState getBreakerFromRedis(String modelName) {
        RMap<String, String> map = breakerMap(modelName);
        String stateStr = map.get("state");
        if (stateStr == null) return null;

        CircuitBreakerState breaker = new CircuitBreakerState(failureThreshold, recoveryWaitMs);
        try {
            breaker.setState(CircuitBreakerState.State.valueOf(stateStr));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String failuresStr = map.get("failures");
        if (failuresStr != null) {
            breaker.setConsecutiveFailures(Long.parseLong(failuresStr));
        }
        breaker.resetOpenedAt(); // will be recalculated from Redis on next use
        return breaker;
    }

    private RMap<String, String> breakerMap(String modelName) {
        return redisson.getMap(KEY_PREFIX + modelName, StringCodec.INSTANCE);
    }

    private long incrementRedisFailures(RMap<String, String> map) {
        String current = map.get("failures");
        long count = (current != null ? Long.parseLong(current) : 0) + 1;
        map.fastPut("failures", String.valueOf(count));
        return count;
    }

    // -- local operations --

    private CircuitBreakerState localBreakerFor(String modelName) {
        return localBreakers.computeIfAbsent(modelName,
                k -> new CircuitBreakerState(failureThreshold, recoveryWaitMs));
    }
}
