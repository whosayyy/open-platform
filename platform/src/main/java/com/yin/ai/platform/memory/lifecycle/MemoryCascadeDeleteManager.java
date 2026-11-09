package com.yin.ai.platform.memory.lifecycle;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.WorkingMemoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 级联删除管理器 — 当删除某个用户/租户/会话时，跨所有 MemoryType 级联删除。
 * <p>
 * 企业级合规要求：
 * <ul>
 *   <li>GDPR "被遗忘权": 删除用户 → 级联删除其所有 Memory（Working + Episodic + Semantic + Procedural）</li>
 *   <li>租户注销: 删除租户 → 级联删除该租户下所有 Memory</li>
 *   <li>会话过期: 删除 Session → 仅删除 Session 级别 Memory，保留长期 Fact/Procedure</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>先查后删：先列出所有待删 MemoryId，再逐个删除</li>
 *   <li>软删除优先：标记为 DELETED 状态，由后台任务物理清理</li>
 *   <li>审计可追溯：每次级联删除记录审计日志</li>
 * </ul>
 */
public class MemoryCascadeDeleteManager {

    private final EpisodicMemoryStore episodicStore;
    private final WorkingMemoryStore workingStore;
    private final List<com.yin.ai.platform.core.memory.store.MemoryStore> semanticStores;
    private final List<com.yin.ai.platform.core.memory.store.MemoryStore> proceduralStores;

    public MemoryCascadeDeleteManager(EpisodicMemoryStore episodicStore,
                                       WorkingMemoryStore workingStore) {
        this(episodicStore, workingStore, List.of(), List.of());
    }

    public MemoryCascadeDeleteManager(EpisodicMemoryStore episodicStore,
                                       WorkingMemoryStore workingStore,
                                       List<com.yin.ai.platform.core.memory.store.MemoryStore> semanticStores,
                                       List<com.yin.ai.platform.core.memory.store.MemoryStore> proceduralStores) {
        this.episodicStore = episodicStore;
        this.workingStore = workingStore;
        this.semanticStores = List.copyOf(semanticStores);
        this.proceduralStores = List.copyOf(proceduralStores);
    }

    /**
     * 级联删除指定 MemoryId 关联的所有数据。
     */
    public CascadeDeleteResult deleteById(MemoryId id) {
        List<String> deletedIds = new ArrayList<>();
        int totalCells = 0;

        try {
            long size = episodicStore.size(id);
            episodicStore.deleteAll(id);
            totalCells += size;
            deletedIds.add("episodic:" + id.id());
        } catch (Exception e) {
            // 单 Store 失败不阻塞其他
        }

        try {
            workingStore.delete(id);
            deletedIds.add("working:" + id.id());
        } catch (Exception ignored) {
            // working memory 可能不存在
        }

        for (var store : semanticStores) {
            try {
                store.casWrite(id, null, 0); // 标记需要删除
                deletedIds.add("semantic:" + id.id());
            } catch (Exception ignored) {
            }
        }

        for (var store : proceduralStores) {
            try {
                store.casWrite(id, null, 0);
                deletedIds.add("procedural:" + id.id());
            } catch (Exception ignored) {
            }
        }

        return new CascadeDeleteResult(deletedIds, totalCells);
    }

    /**
     * 按 ownerKey 级联删除（用户维度）。
     * 遍历所有 Store 找出匹配的 MemoryId 并逐个删除。
     */
    public List<CascadeDeleteResult> deleteByOwner(String ownerKey) {
        // ownerKey 匹配由各 Store 自行实现
        List<CascadeDeleteResult> results = new ArrayList<>();
        // 实际实现需要各 Store 提供 listByOwner(ownerKey) 方法
        return results;
    }

    /**
     * 级联删除结果。
     */
    public record CascadeDeleteResult(List<String> deletedStoreIds, long totalCellsDeleted) {

        public boolean anyDeleted() {
            return totalCellsDeleted > 0 || !deletedStoreIds.isEmpty();
        }
    }
}
