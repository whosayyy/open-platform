package com.yin.ai.platform.engine.config;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 引擎并发防护 — 基于 Semaphore 的背压控制。
 * <p>
 * 虚拟线程模式下不再通过线程池大小控制并发，改用信号量限制
 * 同时在执行的 LLM/Tool/RAG 任务数，防止打爆下游 API rate limit。
 */
@Slf4j
public class EngineConcurrencyGuard {

    private final Semaphore semaphore;
    private final LongAdder rejectedCount = new LongAdder();
    private final LongAdder acquiredCount = new LongAdder();

    public EngineConcurrencyGuard(int maxConcurrency) {
        this.semaphore = new Semaphore(maxConcurrency, true); // fair = true
        log.info("[ENGINE-GUARD] 并发上限={}", maxConcurrency);
    }

    public boolean tryAcquire(long timeoutMs) {
        try {
            boolean ok = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (ok) {
                acquiredCount.increment();
            } else {
                rejectedCount.increment();
                log.warn("[ENGINE-GUARD] 并发拒绝: active={}, rejected={}",
                        getActiveCount(), rejectedCount.sum());
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedCount.increment();
            return false;
        }
    }

    public void release() {
        semaphore.release();
    }

    public int getActiveCount() {
        return acquiredCount.intValue() - semaphore.availablePermits();
    }

    public long getRejectedCount() {
        return rejectedCount.sum();
    }

    public int getMaxConcurrency() {
        return semaphore.availablePermits() + getActiveCount();
    }
}
