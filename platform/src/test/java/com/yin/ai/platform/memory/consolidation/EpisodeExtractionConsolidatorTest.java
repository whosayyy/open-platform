package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EpisodeExtractionConsolidator")
class EpisodeExtractionConsolidatorTest {

    private EpisodeExtractionConsolidator consolidator;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        consolidator = new EpisodeExtractionConsolidator();
        memoryId = MemoryId.of("timeline-1", MemoryType.EPISODIC, "user-1");
    }

    @Test
    @DisplayName("空 cells 返回空结果")
    void shouldReturnEmptyForNullCells() {
        ConsolidationResult result = consolidator.consolidate(memoryId, null);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("少量 cells 合并为单个 Episode")
    void shouldCreateSingleEpisode() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "Hello", Instant.now()),
                cellOf("c2", "World", Instant.now()),
                cellOf("c3", "How are you", Instant.now()));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.episodes()).hasSize(1);
        assertThat(result.episodes().get(0).cellCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("时间间隔超阈值时切分 Episode")
    void shouldSplitByTimeGap() {
        Instant base = Instant.now();
        List<MemoryCell> cells = List.of(
                cellOf("c1", "msg1", base),
                cellOf("c2", "msg2", base.plusSeconds(10)),
                // 60 分钟后 → 新 Episode
                cellOf("c3", "msg3", base.plus(Duration.ofHours(1))),
                cellOf("c4", "msg4", base.plus(Duration.ofHours(1)).plusSeconds(10)));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.episodes()).hasSize(2);
        assertThat(result.episodes().get(0).cellCount()).isEqualTo(2);
        assertThat(result.episodes().get(1).cellCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("数量超限时强制切分 Episode")
    void shouldSplitByMaxCells() {
        EpisodeExtractionConsolidator strict = new EpisodeExtractionConsolidator(
                Duration.ofHours(24), 3); // max 3 cells per episode

        List<MemoryCell> cells = IntStream.range(0, 7)
                .mapToObj(i -> cellOf("c" + i, "msg-" + i, Instant.now()))
                .toList();

        ConsolidationResult result = strict.consolidate(memoryId, cells);

        assertThat(result.episodes()).hasSize(3); // 3 + 3 + 1
        assertThat(result.episodes().get(0).cellCount()).isEqualTo(3);
        assertThat(result.episodes().get(1).cellCount()).isEqualTo(3);
        assertThat(result.episodes().get(2).cellCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("episode title 是第一个 cell 的前 80 字符")
    void shouldUseFirstCellAsTitle() {
        String longText = "A".repeat(100);
        List<MemoryCell> cells = List.of(
                cellOf("c1", longText, Instant.now()),
                cellOf("c2", "second", Instant.now()));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.episodes()).hasSize(1);
        assertThat(result.episodes().get(0).title()).hasSizeLessThanOrEqualTo(83); // 80 + "..."
    }

    @Test
    @DisplayName("consolidatedCellIds 记录所有已处理 cell")
    void shouldRecordProcessedCells() {
        List<MemoryCell> cells = List.of(
                cellOf("a", "msg1", Instant.now()),
                cellOf("b", "msg2", Instant.now()));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.consolidatedCellIds()).containsExactly("a", "b");
    }

    private static MemoryCell cellOf(String id, String content, Instant timestamp) {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
        return new MemoryCell(cell.cellId(), cell.memoryType(), cell.content(),
                cell.attributes(), timestamp, cell.sequenceNumber(), cell.vector(),
                cell.tags(), cell.parentCellId(), cell.version());
    }
}
