package com.yin.ai.platform.memory.compression;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 摘要压缩器 — 通过外部摘要函数将多条 Cell 合并为一条摘要 Cell。
 * <p>
 * 摘要函数由调用方注入（例如 LLM 服务、规则引擎），压缩器本身只负责编排：
 * <ol>
 *   <li>调用摘要函数生成摘要文本</li>
 *   <li>创建新的摘要 Cell（标记为 DERIVED_FROM 原始 Cell）</li>
 *   <li>返回压缩后的单条 Cell 列表</li>
 * </ol>
 * <p>
 * 注意：实际 LLM 调用应在 infrastructure 层实现，通过 {@code Function<List<MemoryCell>, String>}
 * 注入。本类保持对存储层的零依赖。
 */
@Slf4j
public class SummarizationCompressor implements MemoryCompressor {

    private final Function<List<MemoryCell>, String> summarizeFn;

    /**
     * @param summarizeFn 摘要函数，输入 Cell 列表，输出摘要文本
     */
    public SummarizationCompressor(Function<List<MemoryCell>, String> summarizeFn) {
        this.summarizeFn = summarizeFn;
    }

    @Override
    public List<MemoryCell> compress(List<MemoryCell> cells, CompressionContext ctx) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        if (cells.size() == 1) {
            return new ArrayList<>(cells);
        }

        String summary = summarizeFn.apply(cells);
        MemoryCell first = cells.get(0);
        MemoryCell last = cells.get(cells.size() - 1);

        MemoryCell summaryCell = MemoryCell.of(
                MemoryCellId.of("summary:" + UUID.randomUUID()),
                first.memoryType(),
                summary);
        summaryCell = summaryCell.withAttribute("compressedCount", cells.size());
        summaryCell = summaryCell.withAttribute("originalSpanFrom", first.timestamp().toString());
        summaryCell = summaryCell.withAttribute("originalSpanTo", last.timestamp().toString());

        log.debug("[SUMMARIZE] compressed {} cells → 1 summary ({} chars)",
                cells.size(), summary.length());

        return List.of(summaryCell);
    }

    @Override
    public CompressionStrategy strategy() {
        return CompressionStrategy.SUMMARIZE;
    }
}
