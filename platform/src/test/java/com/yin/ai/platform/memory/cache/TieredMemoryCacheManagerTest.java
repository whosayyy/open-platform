package com.yin.ai.platform.memory.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TieredMemoryCacheManager")
class TieredMemoryCacheManagerTest {

    private TieredMemoryCacheManager cacheManager;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        cacheManager = new TieredMemoryCacheManager(100, Duration.ofMinutes(1));
        memoryId = MemoryId.of("test-1", MemoryType.EPISODIC, "user-1");
    }

    @Test
    @DisplayName("cellList 缓存读写")
    void shouldCacheCellList() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "hello");
        List<MemoryCell> cells = List.of(cell);

        assertThat(cacheManager.getCellList(memoryId)).isNull();
        cacheManager.putCellList(memoryId, cells);
        assertThat(cacheManager.getCellList(memoryId)).hasSize(1);
    }

    @Test
    @DisplayName("cellList 空列表不缓存")
    void shouldNotCacheEmptyCellList() {
        cacheManager.putCellList(memoryId, List.of());
        assertThat(cacheManager.getCellList(memoryId)).isNull();
    }

    @Test
    @DisplayName("cellList 失效")
    void shouldInvalidateCellList() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "hello");
        cacheManager.putCellList(memoryId, List.of(cell));
        cacheManager.invalidateCellList(memoryId);
        assertThat(cacheManager.getCellList(memoryId)).isNull();
    }

    @Test
    @DisplayName("单 Cell 缓存读写")
    void shouldCacheSingleCell() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");

        cacheManager.putCell(memoryId, cell);
        MemoryCell cached = cacheManager.getCell(memoryId, cell.cellId().id());
        assertThat(cached).isNotNull();
        assertThat(cached.content()).isEqualTo("data");
    }

    @Test
    @DisplayName("单 Cell 失效")
    void shouldInvalidateCell() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        cacheManager.putCell(memoryId, cell);
        cacheManager.invalidateCell(memoryId, cell.cellId().id());
        assertThat(cacheManager.getCell(memoryId, cell.cellId().id())).isNull();
    }

    @Test
    @DisplayName("Working Memory 缓存读写")
    void shouldCacheWorkingMemory() {
        String snapshot = "working-memory-snapshot";
        cacheManager.putWorkingMemory(memoryId, snapshot);
        assertThat(cacheManager.<String>getWorkingMemory(memoryId)).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("invalidateAll(MemoryId) 清空关联缓存")
    void shouldInvalidateAllByMemoryId() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        cacheManager.putCellList(memoryId, List.of(cell));
        cacheManager.putCell(memoryId, cell);
        cacheManager.putWorkingMemory(memoryId, "snap");

        cacheManager.invalidateAll(memoryId);

        assertThat(cacheManager.getCellList(memoryId)).isNull();
        assertThat(cacheManager.getCell(memoryId, cell.cellId().id())).isNull();
        assertThat(cacheManager.<String>getWorkingMemory(memoryId)).isNull();
    }

    @Test
    @DisplayName("invalidateAll() 清空全部")
    void shouldInvalidateAllCaches() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        cacheManager.putCellList(memoryId, List.of(cell));
        cacheManager.putCell(memoryId, cell);
        cacheManager.putWorkingMemory(memoryId, "snap");

        cacheManager.invalidateAll();

        assertThat(cacheManager.getCellList(memoryId)).isNull();
        assertThat(cacheManager.getCell(memoryId, cell.cellId().id())).isNull();
        assertThat(cacheManager.<String>getWorkingMemory(memoryId)).isNull();
    }

    @Test
    @DisplayName("聚合统计")
    void shouldAggregateStats() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        cacheManager.putCellList(memoryId, List.of(cell));
        cacheManager.getCellList(memoryId); // hit
        cacheManager.getCellList(MemoryId.of("miss", MemoryType.EPISODIC, "u")); // miss

        TieredMemoryCacheManager.AggregateStats stats = cacheManager.aggregateStats();
        assertThat(stats.totalRequestCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("命名缓存扩展点")
    void shouldSupportNamedCaches() {
        Cache<String, Object> named = cacheManager.getOrCreateCache("custom");
        named.put("key", "value");
        assertThat(named.getIfPresent("key")).isEqualTo("value");

        // 重复获取返回同一实例
        Cache<String, Object> same = cacheManager.getOrCreateCache("custom");
        assertThat(same).isSameAs(named);
    }

    @Test
    @DisplayName("estimatedSize 反映缓存条目数")
    void shouldEstimateSize() {
        assertThat(cacheManager.estimatedSize()).isEqualTo(0);

        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "data");
        cacheManager.putCellList(memoryId, List.of(cell));
        cacheManager.putCell(memoryId, cell);
        cacheManager.putWorkingMemory(memoryId, "snap");

        assertThat(cacheManager.estimatedSize()).isGreaterThanOrEqualTo(3);
    }
}
