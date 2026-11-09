package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HybridRetriever RRF 融合")
class HybridRetrieverTest {

    private HybridRetriever retriever;
    private List<MemoryCell> cells;

    @BeforeEach
    void setUp() {
        retriever = new HybridRetriever();
        cells = List.of(
                cellOf("c1", "Java Spring Boot microservices"),
                cellOf("c2", "Python machine learning TensorFlow"),
                cellOf("c3", "Docker Kubernetes container orchestration"),
                cellOf("c4", "Java design patterns"),
                cellOf("c5", "JavaScript React frontend development"));
    }

    @Test
    @DisplayName("无 vector retriever 时纯 keyword 召回")
    void shouldUseKeywordOnlyWhenNoVector() {
        assertThat(retriever.hasVectorRetriever()).isFalse();
        MemoryQuery query = MemoryQuery.byText("Java", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(r -> r.cell().content().toLowerCase().contains("java"));
    }

    @Test
    @DisplayName("纯 keyword 路径 topK 限制生效")
    void shouldLimitTopK() {
        MemoryQuery query = MemoryQuery.byText("Java Docker", 2);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("空结果返回空列表")
    void shouldReturnEmptyForNoMatch() {
        MemoryQuery query = MemoryQuery.byText("Ruby Rails ActiveRecord", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("strategy 返回 HYBRID")
    void shouldReturnHybridStrategy() {
        assertThat(retriever.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    @DisplayName("RRF 融合去重（同 cellId 合并）")
    void shouldDeduplicateInRRFFusion() {
        // keyword results: c1(rank1), c4(rank2)
        // vector would return same cells but different order
        // RRF should merge them
        MemoryQuery query = MemoryQuery.byText("Java", 5);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);

        // Each cellId should appear at most once
        long distinctCount = results.stream()
                .map(r -> r.cell().cellId().id())
                .distinct()
                .count();
        assertThat(distinctCount).isEqualTo(results.size());
    }

    private static MemoryCell cellOf(String id, String content) {
        return MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
    }
}
