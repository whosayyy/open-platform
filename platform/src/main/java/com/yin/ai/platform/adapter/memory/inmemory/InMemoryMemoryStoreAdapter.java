package com.yin.ai.platform.adapter.memory.inmemory;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import com.yin.ai.platform.core.memory.store.WorkingMemoryStore;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * InMemory 适配器 — 基于 {@link ConcurrentHashMap} 的全 Memory 类型存储。
 * <p>
 * 实现 {@link WorkingMemoryStore} 和 {@link EpisodicMemoryStore}，
 * 用于开发和测试环境。不持久化，进程重启数据丢失。
 * 生产环境替换为 Redis 系列适配器。
 * <p>
 * 高并发保障：所有 Map 操作用 ConcurrentHashMap computeIfAbsent + synchronized List，
 * 确保追加操作的原子性和可见性。
 */
public class InMemoryMemoryStoreAdapter implements WorkingMemoryStore, EpisodicMemoryStore {

    private final Map<String, WorkingMemory> workingMemoryMap = new ConcurrentHashMap<>();
    private final Map<String, List<ResumePoint>> resumePointsMap = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryCell>> episodicMap = new ConcurrentHashMap<>();
    private final AtomicLong globalSequence = new AtomicLong(0);

    // ==================== MemoryStore ====================

    @Override
    public StoreType storeType() {
        return StoreType.IN_MEMORY;
    }

    @Override
    public boolean supports(MemoryType memoryType) {
        return true; // InMemory 支持所有类型
    }

    @Override
    public StoreHealth health() {
        return StoreHealth.healthy(0);
    }

    // ==================== WorkingMemoryStore ====================

    @Override
    public void save(WorkingMemory wm) {
        workingMemoryMap.put(key(wm.getId()), wm);
    }

    @Override
    public Optional<WorkingMemory> load(MemoryId id) {
        return Optional.ofNullable(workingMemoryMap.get(key(id)));
    }

    @Override
    public void saveResumePoint(MemoryId id, ResumePoint point) {
        resumePointsMap.computeIfAbsent(key(id), k ->
                Collections.synchronizedList(new ArrayList<>())).add(point);
    }

    @Override
    public Optional<ResumePoint> loadResumePoint(MemoryId id, String label) {
        List<ResumePoint> points = resumePointsMap.getOrDefault(key(id), List.of());
        return points.stream()
                .filter(rp -> rp.label().equals(label))
                .reduce((first, second) -> second);
    }

    @Override
    public List<ResumePoint> listResumePoints(MemoryId id) {
        return List.copyOf(resumePointsMap.getOrDefault(key(id), List.of()));
    }

    // ==================== EpisodicMemoryStore ====================

    @Override
    public MemoryCell append(MemoryId id, MemoryCell cell) {
        long seq = globalSequence.incrementAndGet();
        MemoryCell stamped = cell.withSequenceNumber(seq);
        getEpisodicList(id).add(stamped);
        return stamped;
    }

    @Override
    public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells) {
        List<MemoryCell> result = new ArrayList<>();
        for (MemoryCell cell : cells) {
            result.add(append(id, cell));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<MemoryCell> readTimeline(MemoryId id, int limit) {
        List<MemoryCell> cells = getEpisodicList(id);
        synchronized (cells) {
            if (cells.size() <= limit) {
                return new ArrayList<>(cells);
            }
            return new ArrayList<>(cells.subList(cells.size() - limit, cells.size()));
        }
    }

    @Override
    public List<MemoryCell> readAll(MemoryId id) {
        return List.copyOf(getEpisodicList(id));
    }

    @Override
    public long size(MemoryId id) {
        return getEpisodicList(id).size();
    }

    @Override
    public void deleteAll(MemoryId id) {
        episodicMap.remove(key(id));
    }

    @Override
    public boolean deleteCell(MemoryId id, MemoryCellId cellId) {
        String k = key(id);
        final boolean[] removed = {false};
        episodicMap.compute(k, (unused, list) -> {
            if (list == null || list.isEmpty()) {
                return list;
            }
            List<MemoryCell> retained = new ArrayList<>();
            synchronized (list) {
                for (MemoryCell cell : list) {
                    if (cell.cellId().equals(cellId)) {
                        removed[0] = true;
                    } else {
                        retained.add(cell);
                    }
                }
            }
            if (!removed[0]) {
                return list;
            }
            return retained.isEmpty() ? null : retained;
        });
        return removed[0];
    }

    @Override
    public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        List<MemoryCell> cells = getEpisodicList(id);
        synchronized (cells) {
            for (int i = 0; i < cells.size(); i++) {
                MemoryCell existing = cells.get(i);
                if (existing.cellId().equals(cell.cellId())) {
                    if (existing.version() == expectedVersion) {
                        cells.set(i, cell.withVersion(expectedVersion + 1));
                        return true;
                    }
                    return false;
                }
            }
            // Cell not found, treat version 0 as insert
            if (expectedVersion == 0) {
                cells.add(cell.withVersion(1));
                return true;
            }
            return false;
        }
    }

    // ==================== delete ====================

    @Override
    public void delete(MemoryId id) {
        workingMemoryMap.remove(key(id));
        resumePointsMap.remove(key(id));
        episodicMap.remove(key(id));
    }

    // ==================== internal ====================

    /**
     * 存储 key：含 ownerKey（tenant）前缀，防止跨用户同 id 碰撞；
     * ownerKey 为空（系统内部记忆）保持原格式。
     */
    private String key(MemoryId id) {
        if (id.ownerKey() == null || id.ownerKey().isBlank()) {
            return id.id();
        }
        return id.ownerKey() + ":" + id.id();
    }

    private List<MemoryCell> getEpisodicList(MemoryId id) {
        return episodicMap.computeIfAbsent(key(id),
                k -> Collections.synchronizedList(new ArrayList<>()));
    }
}
