package com.yin.ai.platform.knowledge;

import com.yin.ai.platform.document.model.DocumentSegment;

import java.util.List;

/**
 * KnowledgeBase SPI — 知识库抽象。
 * <p>
 * Agent 只关心要查询哪个知识库，不关心底层是向量检索、关键词检索、知识图谱还是 HTTP API。
 * 不同后端对应不同实现：
 * <ul>
 *   <li>{@code VectorKnowledgeBase} → ES 向量检索</li>
 *   <li>{@code HybridKnowledgeBase} → 向量 + BM25 混合检索</li>
 *   <li>{@code GraphKnowledgeBase} → 知识图谱查询</li>
 * </ul>
 * <p>
 * 约束：不包含业务逻辑、不调用 LLM、不感知调用方。
 */
public interface KnowledgeBase {

    /** 知识库唯一名称，对应 Skill YAML 中 {@code knowledge: [name]}。 */
    String getName();

    /**
     * 执行知识检索并返回组装好的 Markdown 上下文字符串。
     *
     * @param query 用户查询文本
     * @return 检索到的知识上下文（Markdown 格式）；无结果或检索失败返回空字符串
     */
    String retrieve(String query);

    /**
     * 将文档分段索引到知识库。
     * 默认空实现 — 仅向量类知识库需要覆写。
     *
     * @param segments 文档分段列表
     */
    default void index(List<DocumentSegment> segments) {
    }
}
