package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FactExtractionConsolidator")
class FactExtractionConsolidatorTest {

    private FactExtractionConsolidator consolidator;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        consolidator = new FactExtractionConsolidator();
        memoryId = MemoryId.of("timeline-1", MemoryType.EPISODIC, "user-1");
    }

    @Test
    @DisplayName("空 cells 返回空结果")
    void shouldReturnEmptyForNullCells() {
        ConsolidationResult result = consolidator.consolidate(memoryId, null);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("提取 'X 是 Y' 模式")
    void shouldExtractIsPattern() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "Java 是一门编程语言"));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).subject()).isEqualTo("Java");
        assertThat(result.facts().get(0).predicate()).isEqualTo("是");
        assertThat(result.facts().get(0).object()).isEqualTo("一门编程语言");
    }

    @Test
    @DisplayName("提取 'X likes Y' 模式")
    void shouldExtractLikesPattern() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "Alice likes Bob"));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).subject()).isEqualTo("Alice");
        assertThat(result.facts().get(0).predicate()).isEqualTo("喜欢");
        assertThat(result.facts().get(0).object()).isEqualTo("Bob");
    }

    @Test
    @DisplayName("提取 'X 知道 Y' 模式")
    void shouldExtractKnowsPattern() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "小张 知道 这个秘密"));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).predicate()).isEqualTo("知道");
    }

    @Test
    @DisplayName("无匹配模式时返回空 facts")
    void shouldReturnEmptyWhenNoPatternMatch() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "今天天气不错"),
                cellOf("c2", "你好世界"));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.facts()).isEmpty();
    }

    @Test
    @DisplayName("多模式同时提取")
    void shouldExtractMultiplePatterns() {
        List<MemoryCell> cells = List.of(
                cellOf("c1", "北京是中国的首都，上海位于东部"));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.facts()).hasSize(2);
    }

    @Test
    @DisplayName("consolidatedCellIds 记录产生 fact 的 cell")
    void shouldRecordCellsThatProducedFacts() {
        List<MemoryCell> cells = List.of(
                cellOf("f1", "Java is great"),
                cellOf("f2", "no pattern here"));

        ConsolidationResult result = consolidator.consolidate(memoryId, cells);

        assertThat(result.consolidatedCellIds()).containsExactly("f1");
    }

    private static MemoryCell cellOf(String id, String content) {
        return MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
    }
}
