package com.yin.ai.platform.memory.concurrency;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.exception.MemoryConcurrencyException;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CAS 乐观锁并发测试")
class CasWriteConcurrencyTest {

    private DefaultMemoryRuntime runtime;
    private InMemoryMemoryStoreAdapter store;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStoreAdapter();
        runtime = new DefaultMemoryRuntime(
                new DefaultWorkingMemoryManager(store), store);
        memoryId = MemoryId.of("cas-test", MemoryType.EPISODIC, "user-1");
    }

    @Test
    @DisplayName("CAS 写入成功后版本号递增")
    void shouldIncrementVersionOnCasWrite() {
        MemoryCellId cellId = MemoryCellId.of("cell-1");
        // 首次 CAS 写入（插入）：版本 0 → 1
        MemoryCell first = runtime.casAppend(memoryId, cellId, "v1", MemoryContext.empty());
        assertThat(first.version()).isEqualTo(1);

        // 第二次 CAS 写入同一 cellId（更新）：版本 1 → 2
        MemoryCell second = runtime.casAppend(memoryId, cellId, "v2", MemoryContext.empty());
        assertThat(second.version()).isEqualTo(2);

        List<MemoryCell> cells = store.readAll(memoryId);
        assertThat(cells).hasSize(1); // 同一个 cellId，覆盖写入
    }

    @Test
    @DisplayName("并发 CAS 写入触发乐观锁重试")
    void shouldRetryOnCasConflict() throws Exception {
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        MemoryCellId cellId = MemoryCellId.of("concurrent-cell");
        runtime.append(memoryId, "base", MemoryContext.empty());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    runtime.casAppend(memoryId, cellId, "update-" + index,
                            MemoryContext.empty());
                    successCount.incrementAndGet();
                } catch (MemoryConcurrencyException e) {
                    conflictCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("超过最大重试次数抛出异常")
    void shouldThrowAfterMaxRetries() {
        // 使用始终返回 false 的 CAS 实现模拟无限版本冲突
        InMemoryMemoryStoreAdapter alwaysFailingStore = new InMemoryMemoryStoreAdapter() {
            @Override
            public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
                return false;
            }
        };
        DefaultMemoryRuntime failingRuntime = new DefaultMemoryRuntime(
                new DefaultWorkingMemoryManager(alwaysFailingStore), alwaysFailingStore);
        MemoryId id = MemoryId.of("always-fail", MemoryType.EPISODIC, "user");
        failingRuntime.append(id, "base", MemoryContext.empty());

        assertThrows(MemoryConcurrencyException.class, () -> {
            failingRuntime.casAppend(id, MemoryCellId.of("retry-limit-cell"),
                    "should-fail", MemoryContext.empty());
        });
    }

    @Test
    @DisplayName("多线程并发追加无数据丢失")
    void shouldNotLoseDataOnConcurrentAppend() throws Exception {
        int threadCount = 8;
        int messagesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < messagesPerThread; i++) {
                        runtime.append(memoryId,
                                "msg-" + threadId + "-" + i,
                                MemoryContext.empty());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(store.size(memoryId)).isEqualTo(threadCount * messagesPerThread);
    }

    @Test
    @DisplayName("InMemory CAS 版本不匹配返回 false")
    void shouldReturnFalseOnVersionMismatch() {
        MemoryCellId cellId = MemoryCellId.of("version-cell");
        MemoryCell cell = MemoryCell.of(cellId, MemoryType.EPISODIC, "data");
        store.append(memoryId, cell);

        // 用错误的 expectedVersion
        boolean result = store.casWrite(memoryId,
                cell.withVersion(5), 5);
        assertFalse(result);
    }
}
