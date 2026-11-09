package com.yin.ai.platform.engine.config;

import com.yin.ai.platform.engine.DefaultExecutionEngine;
import com.yin.ai.platform.engine.EngineProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * 执行引擎配置 — 基于 Java 21 虚拟线程的统一执行器。
 * <p>
 * 关键设计决策：
 * <ul>
 *   <li>使用 {@link Executors#newVirtualThreadPerTaskExecutor()} 而非线程池 —
 *       虚拟线程成本极低（~1KB 栈），无需池化复用</li>
 *   <li>通过 {@link DistributedConcurrencyGuard}（本地 Semaphore + Redis 全局计数器）控制并发度上限</li>
 *   <li>所有异步/并行/超时/重试统一走 {@link DefaultExecutionEngine}</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class EngineConfiguration {

    @Bean("engineTaskExecutor")
    public ExecutorService engineTaskExecutor(EngineProperties properties) {
        EngineProperties.ThreadPool cfg = properties.getThreadPool();

        ThreadFactory factory = Thread.ofVirtual()
                .name(cfg.getThreadNamePrefix(), 1)
                .factory();

        ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);

        log.info("[ENGINE] 虚拟线程执行器初始化: namePrefix={}, 并发上限由 DistributedConcurrencyGuard 控制",
                cfg.getThreadNamePrefix());

        return executor;
    }

    @Bean
    public DistributedConcurrencyGuard engineConcurrencyGuard(EngineProperties properties,
                                                                ObjectProvider<RedissonClient> redissonProvider) {
        int localMax = properties.getThreadPool().getMaxSize();
        int globalMax = properties.getMaxGlobalConcurrency();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson != null) {
            return new DistributedConcurrencyGuard(localMax, globalMax, redisson);
        }
        return new DistributedConcurrencyGuard(localMax);
    }

    @Bean("engineTimeoutScheduler")
    public ScheduledExecutorService engineTimeoutScheduler() {
        ThreadFactory factory = Thread.ofVirtual()
                .name("ai-timeout-", 1)
                .factory();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        log.info("[ENGINE] 超时调度器初始化 (virtual-thread)");
        return scheduler;
    }

    @Bean
    public DefaultExecutionEngine defaultExecutionEngine(
            ExecutorService engineTaskExecutor,
            DistributedConcurrencyGuard concurrencyGuard) {
        return new DefaultExecutionEngine(engineTaskExecutor, concurrencyGuard);
    }

    @Bean
    public MeterBinder engineThreadPoolMetrics() {
        return registry -> {
            Gauge.builder("ai.engine.thread.active",
                    Thread::activeCount)
                    .description("JVM active thread count (approximate virtual thread gauge)")
                    .register(registry);
        };
    }
}
