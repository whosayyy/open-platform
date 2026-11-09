package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.MemoryVector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事实 — Semantic Memory 的基本单元，以三元组 (subject, predicate, object) 表达。
 * <p>
 * 设计为不可变 record，通过 {@link KnowledgeGraph} 进行存储和查询。
 * 每个 Fact 可选择性附带向量嵌入（通过 {@link EmbeddingService} 生成），
 * 用于语义相似度搜索。
 * <p>
 * 来源追溯：{@code sourceCellIds} 记录从哪些 Episodic Cell 提取了此事实，
 * 用于解释性和一致性检验。
 *
 * @param id             唯一标识
 * @param subject        主语
 * @param predicate      谓词
 * @param object         宾语
 * @param confidence     置信度 [0.0, 1.0]
 * @param vector         向量嵌入（可选）
 * @param sourceCellIds  来源 Cell ID 列表
 * @param metadata       扩展元数据
 * @param createdAt      创建时间
 */
public record Fact(
        String id,
        String subject,
        String predicate,
        String object,
        double confidence,
        Optional<MemoryVector> vector,
        List<String> sourceCellIds,
        Map<String, Object> metadata,
        Instant createdAt) {

    public Fact {
        subject = subject != null ? subject : "";
        predicate = predicate != null ? predicate : "";
        object = object != null ? object : "";
        confidence = Math.clamp(confidence, 0.0, 1.0);
        vector = vector != null ? vector : Optional.empty();
        sourceCellIds = sourceCellIds != null ? List.copyOf(sourceCellIds) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static Fact of(String id, String subject, String predicate, String object,
                           double confidence) {
        return new Fact(id, subject, predicate, object, confidence,
                Optional.empty(), List.of(), Map.of(), Instant.now());
    }

    public Fact withVector(MemoryVector embedding) {
        return new Fact(id, subject, predicate, object, confidence,
                Optional.of(embedding), sourceCellIds, metadata, createdAt);
    }

    public Fact withConfidence(double newConfidence) {
        return new Fact(id, subject, predicate, object, newConfidence,
                vector, sourceCellIds, metadata, createdAt);
    }

    public Fact withSourceCell(String cellId) {
        List<String> newSources = new java.util.ArrayList<>(sourceCellIds);
        newSources.add(cellId);
        return new Fact(id, subject, predicate, object, confidence,
                vector, newSources, metadata, createdAt);
    }

    /** 三元组可读表示："subject predicate object" */
    public String toTriple() {
        return subject + " " + predicate + " " + object;
    }
}
