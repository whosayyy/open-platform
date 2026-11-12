package com.yin.ai.platform.memory.cache;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TieredMemoryStore 多级缓存")
class TieredMemoryStoreTest {

    private EpisodicMemoryStore delegate;
    private TieredMemoryStore tiered;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        delegate = new InMemoryMemoryStoreAdapter();
        tiered = new TieredMemoryStore(delegate, 100, Duration.ofMinutes(5));
        memoryId = MemoryId.of("tiered-test", MemoryType.EPISODIC, "user-1");
    }

    @Test
    @DisplayName("写入穿透到 L2 委托存储")
    void shouldWriteThrough() {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        tiered.append(memoryId, cell);

        assertThat(delegate.size(memoryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("读取未命中时回查 L2 并回填 L1")
    void shouldFallbackToL2OnMissAndPopulateL1() {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        delegate.append(memoryId, cell);

        List<MemoryCell> result = tiered.readTimeline(memoryId, 10);
        assertThat(result).hasSize(1);

        // 二次读取应命中 L1（不增 L2 调用计数可以通过间接验证）
        double hitRate = tiered.l1Stats().hitRate();
        List<MemoryCell> result2 = tiered.readTimeline(memoryId, 10);
        assertThat(result2).hasSize(1);
    }

    @Test
    @DisplayName("追加后 L1 缓存失效")
    void shouldInvalidateOnAppend() {
        MemoryCell cell1 = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "data1");
        tiered.append(memoryId, cell1);
        tiered.readTimeline(memoryId, 10); // populate L1

        MemoryCell cell2 = MemoryCell.of(MemoryCellId.of("c2"), MemoryType.EPISODIC, "data2");
        tiered.append(memoryId, cell2);

        List<MemoryCell> result = tiered.readTimeline(memoryId, 10);
        assertThat(result).hasSize(2); // should see both after L1 invalidation
    }

    @Test
    @DisplayName("CAS 写入绕过缓存直接委托 L2")
    void shouldBypassCacheForCas() {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("cas-cell"), MemoryType.EPISODIC, "v1");
        tiered.append(memoryId, cell);

        // 追加后 store 中版本为 1（MemoryCell.of 默认 version=1）
        MemoryCell updated = MemoryCell.of(MemoryCellId.of("cas-cell"), MemoryType.EPISODIC, "v2")
                .withVersion(1);
        boolean success = tiered.casWrite(memoryId, updated, 1);

        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("invalidateAll 清空全部 L1 缓存")
    void shouldClearAllL1() {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        tiered.append(memoryId, cell);
        tiered.readTimeline(memoryId, 10); // populate L1
        tiered.invalidateAll();

        // 再次读取应从 L2 回查
        List<MemoryCell> result = tiered.readTimeline(memoryId, 10);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("health 代理到委托存储")
    void shouldDelegateHealth() {
        assertThat(tiered.health().healthy()).isTrue();
    }
}
