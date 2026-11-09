package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.MemoryVector;

import java.util.List;

/**
 * 知识图谱 SPI — 管理 Fact 的 CRUD 和语义查询。
 * <p>
 * 实现示例：
 * <ul>
 *   <li>InMemory 实现 — 基于 HashMap + 线性搜索，开发/测试用</li>
 *   <li>MySQL 实现 — 三列表 (subject, predicate, object) + JSON metadata</li>
 *   <li>GraphDB (Neo4j) 实现 — 原生图存储 + 图遍历查询</li>
 * </ul>
 * <p>
 * 与 {@link SemanticMemoryStore} 的关系：KnowledgeGraph 是 Fact 级别的抽象，
 * SemanticMemoryStore 是更底层的通用存储 SPI（同时管理 Fact、关系、向量索引等）。
 */
public interface KnowledgeGraph {

    /** 添加单个 Fact，返回分配了 ID 的 Fact */
    Fact addFact(Fact fact);

    /** 批量添加 Fact */
    List<Fact> addFacts(List<Fact> facts);

    /** 精确匹配查询：subject/predicate/object 任意字段匹配（null = 不筛选） */
    List<Fact> queryFacts(String subject, String predicate, String object);

    /** 向量相似度搜索 — 返回按 cosineSimilarity 排序的 top-K Fact */
    List<Fact> searchSimilar(MemoryVector queryVector, int topK);

    /** 删除指定 Fact */
    boolean deleteFact(String factId);

    /** Fact 总数 */
    long size();
}
