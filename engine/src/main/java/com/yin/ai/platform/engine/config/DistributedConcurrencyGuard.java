package com.yin.ai.platform.engine.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 分布式并发防护 — 本地 Semaphore + Redis 全局计数器双层防护。
 * <p>
 * 本地 Semaphore 提供快速路径（无网络开销），Redis 计数器确保跨节点总量控制。
 * 当 Redis 不可用时自动降级为仅本地防护。
 */
@Slf4j
public class DistributedConcurrencyGuard {

    private static final String REDIS_COUNTER_KEY = "ai:engine:active-tasks";

    private final Semaphore localSemaphore;
    private final RedissonClient redisson;
    private final int localMax;
    private final int globalMax;
    private final LongAdder rejectedCount = new LongAdder();

    public DistributedConcurrencyGuard(int localMax, int globalMax, RedissonClient redisson) {
        this.localSemaphore = new Semaphore(localMax, true);
        this.redisson = redisson;
        this.localMax = localMax;
        this.globalMax = globalMax > 0 ? globalMax : localMax;
        log.info("[DIST-GUARD] localMax={}, globalMax={}, redis={}", localMax, this.globalMax,
                redisson != null ? "enabled" : "disabled");
    }

    /**
     * 构造仅本地防护的 guard（无 Redis 时 fallback）。
     */
    public DistributedConcurrencyGuard(int localMax) {
        this(localMax, localMax, null);
    }

    public boolean tryAcquire(long timeoutMs) {
        // 1. 本地快速路径
        try {
            if (!localSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                rejectedCount.increment();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedCount.increment();
            return false;
        }

        // 2. 全局 Redis 检查
        if (redisson != null) {
            try {
                Long current = redisson.getAtomicLong(REDIS_COUNTER_KEY).incrementAndGet();
                if (current > globalMax) {
                    redisson.getAtomicLong(REDIS_COUNTER_KEY).decrementAndGet();
                    localSemaphore.release();
                    rejectedCount.increment();
                    log.warn("[DIST-GUARD] global limit reached: current={}, max={}", current, globalMax);
                    return false;
                }
            } catch (Exception e) {
                // Redis 不可用时降级为仅本地防护
                log.debug("[DIST-GUARD] Redis check failed, falling back to local only: {}", e.getMessage());
            }
        }

        return true;
    }

    public void release() {
        localSemaphore.release();
        if (redisson != null) {
            try {
                redisson.getAtomicLong(REDIS_COUNTER_KEY).decrementAndGet();
            } catch (Exception ignored) { }
        }
    }

    public int getActiveCount() {
        return localMax - localSemaphore.availablePermits();
    }

    public long getRejectedCount() {
        return rejectedCount.sum();
    }

    public int getGlobalCount() {
        if (redisson != null) {
            try {
                return (int) redisson.getAtomicLong(REDIS_COUNTER_KEY).get();
            } catch (Exception ignored) { }
        }
        return getActiveCount();
    }
}
