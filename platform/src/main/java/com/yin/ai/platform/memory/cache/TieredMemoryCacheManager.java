package com.yin.ai.platform.memory.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多级缓存管理器 — 管理多个 Caffeine L1 缓存实例，遵循 {@code MultiLevelCacheTemplate} 模式。
 * <p>
 * 提供三种专用缓存区域：
 * <ul>
 *   <li><b>cellListCache</b> — 批量读取缓存（readTimeline 结果），key 为 "tl:{memoryId}"</li>
 *   <li><b>cellCache</b> — 单 Cell 缓存（CAS 写入后回填），key 为 "cell:{memoryId}:{cellId}"</li>
 *   <li><b>workingMemoryCache</b> — Working Memory 快照缓存，key 为 "wm:{memoryId}"</li>
 * </ul>
 * <p>
 * 与 {@code MultiLevelCacheTemplate} 的关系：本类是 Memory 专用缓存管理器，
 * 提供 L1 Caffeine 的创建、统计和失效管理。L2→L3 回退逻辑由各自的 Store 实现处理。
 * <p>
 * 线程安全：ConcurrentHashMap + Caffeine（内部无锁）组成完全无锁的读路径。
 */
public class TieredMemoryCacheManager {

    private final int maxL1Size;
    private final Duration l1Ttl;
    private final boolean recordStats;

    private final Cache<String, List<MemoryCell>> cellListCache;
    private final Cache<String, MemoryCell> cellCache;
    private final Cache<String, Object> workingMemoryCache;

    /** 按名称管理的附加缓存（扩展点） */
    private final Map<String, Cache<String, Object>> namedCaches = new ConcurrentHashMap<>();

    public TieredMemoryCacheManager(int maxL1Size, Duration l1Ttl, boolean recordStats) {
        this.maxL1Size = maxL1Size;
        this.l1Ttl = l1Ttl;
        this.recordStats = recordStats;

        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxL1Size)
                .expireAfterWrite(l1Ttl);
        if (recordStats) {
            builder.recordStats();
        }

        this.cellListCache = builder.build();
        this.cellCache = builder.build();
        this.workingMemoryCache = builder.build();
    }

    public TieredMemoryCacheManager(int maxL1Size, Duration l1Ttl) {
        this(maxL1Size, l1Ttl, true);
    }

    // ==================== Cell List Cache (readTimeline) ====================

    public List<MemoryCell> getCellList(MemoryId id) {
        return cellListCache.getIfPresent(listKey(id));
    }

    public void putCellList(MemoryId id, List<MemoryCell> cells) {
        if (cells != null && !cells.isEmpty()) {
            cellListCache.put(listKey(id), cells);
        }
    }

    public void invalidateCellList(MemoryId id) {
        cellListCache.invalidate(listKey(id));
    }

    // ==================== Single Cell Cache (CAS write) ====================

    public MemoryCell getCell(MemoryId id, String cellId) {
        return cellCache.getIfPresent(cellKey(id, cellId));
    }

    public void putCell(MemoryId id, MemoryCell cell) {
        cellCache.put(cellKey(id, cell.cellId().id()), cell);
    }

    public void invalidateCell(MemoryId id, String cellId) {
        cellCache.invalidate(cellKey(id, cellId));
    }

    // ==================== Working Memory Cache ====================

    @SuppressWarnings("unchecked")
    public <T> T getWorkingMemory(MemoryId id) {
        return (T) workingMemoryCache.getIfPresent(wmKey(id));
    }

    public void putWorkingMemory(MemoryId id, Object snapshot) {
        workingMemoryCache.put(wmKey(id), snapshot);
    }

    public void invalidateWorkingMemory(MemoryId id) {
        workingMemoryCache.invalidate(wmKey(id));
    }

    // ==================== Named Cache (扩展点) ====================

    public Cache<String, Object> getOrCreateCache(String name) {
        return namedCaches.computeIfAbsent(name, n -> {
            Caffeine<Object, Object> builder = Caffeine.newBuilder()
                    .maximumSize(maxL1Size)
                    .expireAfterWrite(l1Ttl);
            if (recordStats) {
                builder.recordStats();
            }
            return builder.build();
        });
    }

    // ==================== Bulk Operations ====================

    /** 按 MemoryId 清空所有关联缓存 */
    public void invalidateAll(MemoryId id) {
        invalidateCellList(id);
        invalidateWorkingMemory(id);
        cellCache.asMap().keySet().removeIf(k -> k.startsWith("cell:" + ownerPrefix(id) + id.id()));
    }

    /** 清空全部 L1 缓存 */
    public void invalidateAll() {
        cellListCache.invalidateAll();
        cellCache.invalidateAll();
        workingMemoryCache.invalidateAll();
        namedCaches.values().forEach(Cache::invalidateAll);
    }

    // ==================== Stats ====================

    /** 聚合缓存命中率统计 */
    public AggregateStats aggregateStats() {
        return new AggregateStats(
                cellListCache.stats(),
                cellCache.stats(),
                workingMemoryCache.stats());
    }

    public long estimatedSize() {
        return cellListCache.estimatedSize()
                + cellCache.estimatedSize()
                + workingMemoryCache.estimatedSize()
                + namedCaches.values().stream().mapToLong(Cache::estimatedSize).sum();
    }

    // ==================== Keys ====================

    // P1-3 修复：key 加入 ownerKey 前缀，防止跨用户/租户同 MemoryId 互踩缓存

    private static String listKey(MemoryId id) {
        return "tl:" + ownerPrefix(id) + id.id();
    }

    private static String cellKey(MemoryId id, String cellId) {
        return "cell:" + ownerPrefix(id) + id.id() + ":" + cellId;
    }

    private static String wmKey(MemoryId id) {
        return "wm:" + ownerPrefix(id) + id.id();
    }

    private static String ownerPrefix(MemoryId id) {
        return id.ownerKey() != null && !id.ownerKey().isBlank() ? id.ownerKey() + ":" : "";
    }

    /**
     * 聚合缓存统计信息。
     */
    public record AggregateStats(
            CacheStats cellListStats,
            CacheStats cellStats,
            CacheStats workingMemoryStats) {

        public double totalHitRate() {
            double sum = cellListStats.hitRate() + cellStats.hitRate() + workingMemoryStats.hitRate();
            int count = 3;
            return count > 0 ? sum / count : 0;
        }

        public long totalRequestCount() {
            return cellListStats.requestCount()
                    + cellStats.requestCount()
                    + workingMemoryStats.requestCount();
        }
    }
}
