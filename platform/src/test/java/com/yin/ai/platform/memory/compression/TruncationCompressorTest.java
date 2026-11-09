package com.yin.ai.platform.memory.compression;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TruncationCompressor")
class TruncationCompressorTest {

    private TruncationCompressor compressor;
    private CompressionContext ctx;

    @BeforeEach
    void setUp() {
        compressor = new TruncationCompressor();
        ctx = CompressionContext.of(100, MemoryType.EPISODIC);
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
    @DisplayName("Token 未超预算时返回全部")
    void shouldReturnAllWhenUnderBudget() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "short msg"),
                cellOf("c2", "another msg"),
                cellOf("c3", "third msg"));

        List<MemoryCell> result = compressor.compress(cells, ctx);
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("Token 超预算时丢弃最早的消息")
    void shouldDropEarliestWhenOverBudget() {
        // 每条约 100 chars → ~40 tokens. Budget 50 → 只能保留 1 条
        String longContent = "A".repeat(100);
        List<MemoryCell> cells = List.of(
                cellOf("c1", longContent),
                cellOf("c2", longContent),
                cellOf("c3", longContent));

        List<MemoryCell> result = compressor.compress(cells, CompressionContext.of(50, MemoryType.EPISODIC));
        // 每条 ~40 tokens, budget 50 → 保留最后 1 条
        assertThat(result).hasSize(1);
        assertThat(result.get(0).cellId().id()).isEqualTo("c3");
    }

    @Test
    @DisplayName("保留顺序不变")
    void shouldPreserveOrder() {
        List<MemoryCell> cells = IntStream.range(0, 10)
                .mapToObj(i -> cellOf("c" + i, "msg-" + i))
                .toList();

        List<MemoryCell> result = compressor.compress(cells, ctx);
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).timestamp())
                    .isAfterOrEqualTo(result.get(i - 1).timestamp());
        }
    }

    @Test
    @DisplayName("单条 Cell 超预算时保留该 Cell")
    void shouldKeepSingleCellEvenIfOverBudget() {
        String huge = "X".repeat(5000);
        List<MemoryCell> cells = List.of(cellOf("big", huge));

        List<MemoryCell> result = compressor.compress(cells, CompressionContext.of(10, MemoryType.EPISODIC));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("maxTokens=0 返回全部")
    void shouldReturnAllWhenMaxTokensZero() {
        List<MemoryCell> cells = List.of(cellOf("c1", "msg1"), cellOf("c2", "msg2"));
        List<MemoryCell> result = compressor.compress(cells, CompressionContext.of(0, MemoryType.EPISODIC));
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("strategy 返回 TRUNCATE")
    void shouldReturnTruncateStrategy() {
        assertThat(compressor.strategy()).isEqualTo(CompressionStrategy.TRUNCATE);
    }

    private static MemoryCell cellOf(String id, String content) {
        return MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
    }
}
