package com.yin.ai.platform.adapter.memory.milvus;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryVector;
import com.yin.ai.platform.core.memory.semantic.Fact;
import com.yin.ai.platform.core.memory.semantic.SemanticMemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Milvus 向量存储适配器 — 基于 Milvus SDK 实现 Semantic Memory 的向量索引和搜索。
 * <p>
 * 当前为骨架实现（Phase 4 仅定义 SPI + 适配器骨架，完整实现推迟到 Phase 5+）。
 * 当 Milvus SDK 集成就绪后，填充具体向量存储和搜索逻辑。
 * <p>
 * Collection 设计（规划中）：
 * <ul>
 *   <li>{@code ai_fact_vectors} — 存储 Fact 的向量嵌入，维度由 EmbeddingService 决定</li>
 *   <li>索引类型：IVF_FLAT（生产环境可升级为 HNSW）</li>
 * </ul>
 */
@Slf4j
@Deprecated(forRemoval = false)
public class MilvusVectorStoreAdapter implements SemanticMemoryStore {

    // ==================== MemoryStore ====================

    @Override
    public StoreType storeType() {
        return StoreType.MILVUS;
    }

    @Override
    public StoreHealth health() {
        return StoreHealth.unhealthy("Milvus adapter not yet implemented");
    }

    @Override
    public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        log.warn("[Milvus] casWrite not implemented");
        return false;
    }

    // ==================== SemanticMemoryStore ====================

    @Override
    public Fact writeFact(Fact fact) {
        log.warn("[Milvus] writeFact not implemented — only vector indexing, "
                + "use MySQL adapter for fact storage");
        return fact;
    }

    @Override
    public List<Fact> writeFacts(List<Fact> facts) {
        log.warn("[Milvus] writeFacts not implemented");
        return facts;
    }

    @Override
    public List<Fact> queryFacts(String subject, String predicate, String object) {
        log.warn("[Milvus] queryFacts not supported — use MySQL adapter");
        return List.of();
    }

    @Override
    public List<Fact> searchByVector(MemoryVector queryVector, int topK) {
        log.warn("[Milvus] searchByVector not implemented");
        return List.of();
    }

    @Override
    public boolean deleteFact(String factId) {
        log.warn("[Milvus] deleteFact not implemented");
        return false;
    }

    @Override
    public void clearAll() {
        log.warn("[Milvus] clearAll not implemented");
    }

    @Override
    public long factCount() {
        return 0;
    }
}
