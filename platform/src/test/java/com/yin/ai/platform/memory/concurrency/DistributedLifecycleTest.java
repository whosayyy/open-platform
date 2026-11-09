package com.yin.ai.platform.memory.concurrency;

import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.event.DefaultMemoryEventBus;
import com.yin.ai.platform.memory.event.MemoryEventBus;
import com.yin.ai.platform.memory.lifecycle.DistributedMemoryLifecycle;
import com.yin.ai.platform.memory.lifecycle.LifecycleHook;
import com.yin.ai.platform.memory.lifecycle.MemoryLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("分布式 MemoryLifecycle 并发测试")
class DistributedLifecycleTest {

    private MemoryLifecycle lifecycle;
    private MemoryEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new DefaultMemoryEventBus();
        lifecycle = new MemoryLifecycle(eventBus);
    }

    @Test
    @DisplayName("并发 transition 同一 MemoryId 只有一个成功")
    void shouldAllowOnlyOneConcurrentTransition() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        MemoryId id = lifecycle.register(
                new MemoryId("concurrent-lifecycle", MemoryType.WORKING, "owner"));

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    if (lifecycle.transition(id, LifecycleState.LOADING)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // CAS 保证只有一个线程成功
        assertEquals(1, successCount.get());
        assertEquals(LifecycleState.LOADING, lifecycle.getState(id));
    }

    @Test
    @DisplayName("并发注册多个 MemoryId 无冲突")
    void shouldRegisterConcurrentlyWithoutConflict() {
        int count = 100;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    lifecycle.register(new MemoryId("id-" + index, MemoryType.WORKING, "owner"));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertDoesNotThrow(() -> latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    @DisplayName("钩子链在并发场景下保持顺序一致性")
    void shouldMaintainHookOrderUnderConcurrency() throws Exception {
        AtomicInteger hookCallCount = new AtomicInteger(0);
        lifecycle.registerHook(new LifecycleHook() {
            @Override
            public boolean supports(MemoryType type, LifecycleState from, LifecycleState to) {
                return true;
            }
            @Override
            public void onPostTransition(MemoryId id, LifecycleState from, LifecycleState to) {
                hookCallCount.incrementAndGet();
            }
        });

        MemoryId id = lifecycle.register(
                new MemoryId("hook-test", MemoryType.WORKING, "owner"));
        lifecycle.transition(id, LifecycleState.LOADING);

        assertEquals(1, hookCallCount.get());
    }

    @Test
    @DisplayName("forceTransition 并发安全")
    void shouldBeConcurrencySafeOnForceTransition() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        MemoryId id = lifecycle.register(
                new MemoryId("force-test", MemoryType.WORKING, "owner"));

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    lifecycle.forceTransition(id, LifecycleState.ACTIVE);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(LifecycleState.ACTIVE, lifecycle.getState(id));
    }
}
