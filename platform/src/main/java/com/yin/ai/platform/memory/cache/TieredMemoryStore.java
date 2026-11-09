package com.yin.ai.platform.memory.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.MemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多级缓存 MemoryStore — L1 Caffeine（堆内热数据）→ L2 委托 Store（Redis/DB）。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>L1 Caffeine：近因优先，最大 10,000 条目，写入后 5min 过期，读写零锁</li>
 *   <li>L2 委托 Store：可以是 Redis（分布式）或 InMemory（单机测试），透明切换</li>
 *   <li>写穿透（Write-Through）：写入 L2 成功后同步更新 L1，保证缓存一致性</li>
 *   <li>CAS 操作绕过 L1 直接委托 L2，因为版本号必须由权威后端管理</li>
 *   <li>遵循已有 {@code MultiLevelCacheTemplate} 模式，但简化为针对 Memory 场景</li>
 * </ul>
 * <p>
 * 缓存策略：
 * <ul>
 *   <li>TL 读取：先查 L1，未命中则查 L2 并回填 L1</li>
 *   <li>追加：写入 L2 成功后以 key 粒度失效 L1（追加改变列表内容）</li>
 *   <li>批量操作：读全量时跳过 L1（Timeline 全量读取低频且数据量大）</li>
 * </ul>
 */
public class TieredMemoryStore implements EpisodicMemoryStore {

    private final EpisodicMemoryStore delegate;
    private final Cache<String, List<MemoryCell>> cellListCache;
    private final Cache<String, MemoryCell> cellCache;

    public TieredMemoryStore(EpisodicMemoryStore delegate) {
        this(delegate, 10_000, Duration.ofMinutes(5));
    }

    public TieredMemoryStore(EpisodicMemoryStore delegate, int maxL1Size, Duration l1Ttl) {
        this.delegate = delegate;
        this.cellListCache = Caffeine.newBuilder()
                .maximumSize(maxL1Size)
                .expireAfterWrite(l1Ttl)
                .recordStats()
                .build();
        this.cellCache = Caffeine.newBuilder()
                .maximumSize(maxL1Size)
                .expireAfterWrite(l1Ttl)
                .recordStats()
                .build();
    }

    @Override
    public StoreType storeType() {
        return delegate.storeType();
    }

    @Override
    public boolean supports(MemoryType memoryType) {
        return delegate.supports(memoryType);
    }

    @Override
    public StoreHealth health() {
        return delegate.health();
    }

    @Override
    public MemoryCell append(MemoryId id, MemoryCell cell) {
        MemoryCell result = delegate.append(id, cell);
        cellListCache.invalidate(listKey(id));
        return result;
    }

    @Override
    public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells) {
        List<MemoryCell> result = delegate.appendBatch(id, cells);
        cellListCache.invalidate(listKey(id));
        return result;
    }

    @Override
    public List<MemoryCell> readTimeline(MemoryId id, int limit) {
        String key = listKey(id);
        List<MemoryCell> cached = cellListCache.getIfPresent(key);
        if (cached != null && cached.size() <= limit) {
            return cached;
        }
        List<MemoryCell> result = delegate.readTimeline(id, limit);
        if (!result.isEmpty()) {
            cellListCache.put(key, result);
        }
        return result;
    }

    @Override
    public List<MemoryCell> readAll(MemoryId id) {
        // 全量读取不缓存 — 数据量大，低频使用
        return delegate.readAll(id);
    }

    @Override
    public long size(MemoryId id) {
        return delegate.size(id);
    }

    @Override
    public void deleteAll(MemoryId id) {
        delegate.deleteAll(id);
        cellListCache.invalidate(listKey(id));
        cellCache.asMap().keySet().removeIf(k -> k.startsWith(cellKeyPrefix(id)));
    }

    @Override
    public boolean deleteCell(MemoryId id, MemoryCellId cellId) {
        boolean removed = delegate.deleteCell(id, cellId);
        if (removed) {
            cellListCache.invalidate(listKey(id));
            cellCache.asMap().keySet().removeIf(k -> k.startsWith(cellKeyPrefix(id)));
        }
        return removed;
    }

    @Override
    public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        // CAS 操作必须绕过 L1 直接委托 L2，因为版本号由 L2 权威管理
        boolean success = delegate.casWrite(id, cell, expectedVersion);
        if (success) {
            cellCache.put(cellKey(id, cell.cellId().id()), cell);
            cellListCache.invalidate(listKey(id));
        }
        return success;
    }

    /** L1 缓存命中率统计 */
    public CacheStats l1Stats() {
        return cellListCache.stats();
    }

    /** 清空 L1 缓存 */
    public void invalidateAll() {
        cellListCache.invalidateAll();
        cellCache.invalidateAll();
    }

    /** 按 MemoryId 清空 L1 缓存 */
    public void invalidate(MemoryId id) {
        cellListCache.invalidate(listKey(id));
    }

    private static String listKey(MemoryId id) {
        return "tl:" + tenantKey(id);
    }

    private static String cellKey(MemoryId id, String cellId) {
        return "cell:" + tenantKey(id) + ":" + cellId;
    }

    private static String cellKeyPrefix(MemoryId id) {
        return "cell:" + tenantKey(id) + ":";
    }

    /** 含 ownerKey（tenant）前缀，防止跨用户同 id 的 L1 缓存互踩；ownerKey 为空保持原格式。 */
    private static String tenantKey(MemoryId id) {
        if (id.ownerKey() == null || id.ownerKey().isBlank()) {
            return id.id();
        }
        return id.ownerKey() + ":" + id.id();
    }
}
