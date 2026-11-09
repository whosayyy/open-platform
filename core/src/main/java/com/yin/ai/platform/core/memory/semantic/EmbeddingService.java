package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.MemoryVector;

import java.util.List;

/**
 * 向量嵌入服务 SPI — 将文本转换为向量嵌入，用于 Semantic Memory 的语义相似度搜索。
 * <p>
 * 实现示例：
 * <ul>
 *   <li>OpenAI Embedding — text-embedding-ada-002 / text-embedding-3-large</li>
 *   <li>Local Embedding — sentence-transformers / BGE-M3</li>
 *   <li>Custom Embedding — 自训练模型</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>无状态接口 — 每次调用独立，不缓存结果</li>
 *   <li>批量接口 — 一次 embedBatch 调用生成多个向量，减少网络往返</li>
 *   <li>维度透明 — 通过返回的 {@link MemoryVector#dimension()} 获取维度</li>
 * </ul>
 */
@FunctionalInterface
public interface EmbeddingService {

    /** 将单条文本转换为向量嵌入 */
    MemoryVector embed(String text);

    /** 批量转换为向量嵌入（默认循环调用 embed，实现可覆盖以优化批量请求） */
    default List<MemoryVector> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
