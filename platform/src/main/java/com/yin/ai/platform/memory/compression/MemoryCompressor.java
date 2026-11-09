package com.yin.ai.platform.memory.compression;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.List;

/**
 * Memory 压缩器 SPI — 将 Cell 列表压缩为更紧凑的表示。
 * <p>
 * 触发条件由 {@link com.yin.ai.platform.memory.policy.CompressionPolicy} 控制，
 * 压缩器本身是无状态的策略实现。
 * <p>
 * 实现示例：
 * <ul>
 *   <li>{@link SummarizationCompressor} — LLM 摘要</li>
 *   <li>{@link TruncationCompressor} — Token 预算截断</li>
 *   <li>重要性过滤压缩器 — 按 relevance score 保留 top-K</li>
 *   <li>层级压缩器 — detail → summary → gist 多层摘要</li>
 * </ul>
 */
public interface MemoryCompressor {

    /**
     * 压缩 Cell 列表。
     *
     * @param cells 原始 Cell 列表（按时间排序）
     * @param ctx   压缩上下文（预算、类型等）
     * @return 压缩后的 Cell 列表
     */
    List<MemoryCell> compress(List<MemoryCell> cells, CompressionContext ctx);

    /** 压缩策略标识 */
    CompressionStrategy strategy();

    /** 是否支持该 Memory 类型 */
    default boolean supports(MemoryType type) {
        return true;
    }
}
