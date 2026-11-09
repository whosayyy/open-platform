package com.yin.ai.platform.memory.compression;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SummarizationCompressor")
class SummarizationCompressorTest {

    private SummarizationCompressor compressor;
    private CompressionContext ctx;

    @BeforeEach
    void setUp() {
        // 简单拼接摘要函数（不调用 LLM）
        compressor = new SummarizationCompressor(cells -> {
            StringBuilder sb = new StringBuilder("Summary of ").append(cells.size()).append(" cells: ");
            for (MemoryCell c : cells) {
                sb.append(c.content()).append("; ");
            }
            return sb.toString();
        });
        ctx = CompressionContext.of(200, MemoryType.EPISODIC);
    }

    @Test
    @DisplayName("null 列表返回空列表")
    void shouldReturnEmptyForNull() {
        assertThat(compressor.compress(null, ctx)).isEmpty();
    }

    @Test
    @DisplayName("空列表返回空列表")
    void shouldReturnEmptyForEmpty() {
        assertThat(compressor.compress(List.of(), ctx)).isEmpty();
    }

    @Test
    @DisplayName("单条 Cell 原样返回")
    void shouldReturnSingleCellAsIs() {
        MemoryCell cell = cellOf("c1", "hello");
        List<MemoryCell> result = compressor.compress(List.of(cell), ctx);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("hello");
    }

    @Test
    @DisplayName("多条 Cell 合并为一条摘要")
    void shouldMergeMultipleIntoSingleSummary() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "first"),
                cellOf("c2", "second"),
                cellOf("c3", "third"));

        List<MemoryCell> result = compressor.compress(cells, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).contains("Summary of 3 cells");
        assertThat(result.get(0).content()).contains("first");
        assertThat(result.get(0).content()).contains("second");
        assertThat(result.get(0).content()).contains("third");
    }

    @Test
    @DisplayName("摘要 Cell 携带压缩元数据")
    void shouldIncludeCompressionMetadata() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "a"),
                cellOf("c2", "b"));

        List<MemoryCell> result = compressor.compress(cells, ctx);

        assertThat(result).hasSize(1);
        MemoryCell summary = result.get(0);
        assertThat(summary.attributes()).containsKey("compressedCount");
        assertThat(summary.attributes().get("compressedCount")).isEqualTo(2);
        assertThat(summary.attributes()).containsKey("originalSpanFrom");
        assertThat(summary.attributes()).containsKey("originalSpanTo");
    }

    @Test
    @DisplayName("strategy 返回 SUMMARIZE")
    void shouldReturnSummarizeStrategy() {
        assertThat(compressor.strategy()).isEqualTo(CompressionStrategy.SUMMARIZE);
    }

    private static MemoryCell cellOf(String id, String content) {
        return MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
    }
}
