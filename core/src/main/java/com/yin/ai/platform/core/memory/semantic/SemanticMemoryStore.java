package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.MemoryVector;
import com.yin.ai.platform.core.memory.store.MemoryStore;

import java.util.List;

/**
 * Semantic Memory 专用存储 SPI — 管理事实、关系和向量索引。
 * <p>
 * Semantic Memory 的存储需求有别于 Episodic（时间线追加）和 Working（键值快照）：
 * <ul>
 *   <li>事实以三元组形式存储，支持图查询（Neo4j / MySQL 三列表）</li>
 *   <li>向量嵌入存储在专用向量引擎（Milvus / Elasticsearch / pgvector）</li>
 *   <li>事实提取由 {@link com.yin.ai.platform.memory.consolidation.MemoryConsolidator} 触发</li>
 * </ul>
 * <p>
 * 实现示例：
 * <ul>
 *   <li>{@link com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter} — HashMap 实现</li>
 *   <li>{@link com.yin.ai.platform.adapter.memory.jdbc.MySQLMemoryStoreAdapter} — MySQL 三列表</li>
 *   <li>{@link com.yin.ai.platform.adapter.memory.milvus.MilvusVectorStoreAdapter} — Milvus 向量存储</li>
 * </ul>
 */
public interface SemanticMemoryStore extends MemoryStore {

    @Override
    default boolean supports(MemoryType memoryType) {
        return memoryType == MemoryType.SEMANTIC;
    }

    /** 写入单个 Fact */
    Fact writeFact(Fact fact);

    /** 批量写入 Fact */
    List<Fact> writeFacts(List<Fact> facts);

    /** 精确匹配查询 Fact（subject/predicate/object 任意为 null 则不筛选该维度） */
    List<Fact> queryFacts(String subject, String predicate, String object);

    /** 向量相似度搜索 — 返回 top-K 最相似的 Fact */
    List<Fact> searchByVector(MemoryVector queryVector, int topK);

    /** 删除指定 Fact */
    boolean deleteFact(String factId);

    /** 删除指定 Memory 区间的所有 Fact */
    void clearAll();

    /** Fact 总数 */
    long factCount();
}
