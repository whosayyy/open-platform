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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KeywordRetriever")
class KeywordRetrieverTest {

    private KeywordRetriever retriever;
    private List<MemoryCell> cells;

    @BeforeEach
    void setUp() {
        retriever = new KeywordRetriever();
        cells = List.of(
                cellOf("c1", "Java is a programming language"),
                cellOf("c2", "Python is popular for data science"),
                cellOf("c3", "Spring Boot is a Java framework"),
                cellOf("c4", "Docker containers simplify deployment"));
    }

    @Test
    @DisplayName("空查询返回空列表")
    void shouldReturnEmptyForBlankQuery() {
        MemoryQuery query = MemoryQuery.byText("", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("关键词匹配返回相关结果")
    void shouldMatchKeyword() {
        MemoryQuery query = MemoryQuery.byText("Java", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).cell().content()).contains("Java");
    }

    @Test
    @DisplayName("多关键词查询按命中率评分")
    void shouldScoreByTermHitRate() {
        MemoryQuery query = MemoryQuery.byText("Java Spring framework", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);

        assertThat(results).isNotEmpty();
        // "Spring Boot is a Java framework" should score highest (hits 3 terms)
        assertThat(results.get(0).cell().content()).contains("Spring Boot");
    }

    @Test
    @DisplayName("topK 限制返回数量")
    void shouldLimitTopK() {
        MemoryQuery query = MemoryQuery.byText("is", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query.withTopK(2), cells);
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("标签过滤：无匹配标签时返回空")
    void shouldFilterByTag() {
        MemoryQuery query = MemoryQuery.byText("Java", 10);
        // 没有 cell 有 "machine-learning" 标签
        query = new MemoryQuery("Java", null, List.of(), null,
                List.of("machine-learning"), 10, 0, Map.of());
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("无匹配时返回空列表")
    void shouldReturnEmptyWhenNoMatch() {
        MemoryQuery query = MemoryQuery.byText("Rust Cargo compiler", 10);
        List<ScoredMemoryCell> results = retriever.retrieve(query, cells);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("strategy 返回 KEYWORD")
    void shouldReturnKeywordStrategy() {
        assertThat(retriever.strategy()).isEqualTo(RetrievalStrategy.KEYWORD);
    }

    private static MemoryCell cellOf(String id, String content) {
        return MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
    }
}
