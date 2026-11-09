package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.memory.compression.CompressionContext;
import com.yin.ai.platform.memory.compression.CompressionStrategy;
import com.yin.ai.platform.memory.compression.MemoryCompressor;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.retrieval.pipeline.MemorySelector;
import com.yin.ai.platform.memory.retrieval.pipeline.RetrievalPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetrievalPipeline")
class RetrievalPipelineTest {

    private static MemoryCell cell(String id, String content) {
        return MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, content);
    }

    @Test
    @DisplayName("Builder 构建完整管道")
    void shouldBuildPipeline() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .retriever(new MockRetriever())
                .ranker(new MockRanker())
                .selector(new MockSelector())
                .compressor(new MockCompressor())
                .build();
        assertThat(pipeline).isNotNull();
    }

    @Test
    @DisplayName("管道执行四阶段，返回最终压缩结果")
    void shouldExecuteFullPipeline() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .retriever(new MockRetriever())
                .ranker(new MockRanker())
                .selector(new MockSelector())
                .compressor(new MockCompressor())
                .build();

        List<MemoryCell> cells = List.of(cell("c1", "hello"), cell("c2", "world"));
        MemoryQuery query = MemoryQuery.byText("hello", 5);

        RetrievalPipeline.PipelineResult result = pipeline.execute(query, cells, 1000);

        assertThat(result.finalCells()).isNotEmpty();
        assertThat(result.recalledCount()).isGreaterThan(0);
        assertThat(result.totalTime()).isNotNull();
    }

    @Test
    @DisplayName("null retriever 返回空列表不崩溃")
    void shouldNotCrashWithoutRetriever() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .ranker(new MockRanker())
                .selector(new MockSelector())
                .build();

        List<MemoryCell> cells = List.of(cell("c1", "hello"));
        MemoryQuery query = MemoryQuery.byText("hello", 5);

        RetrievalPipeline.PipelineResult result = pipeline.execute(query, cells, 0);

        assertThat(result.recalledCount()).isZero();
        assertThat(result.finalCells()).isEmpty();
    }

    @Test
    @DisplayName("null selector 使用 topK 回退")
    void shouldFallbackWithoutSelector() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .retriever(new MockRetriever())
                .ranker(new MockRanker())
                .build();

        List<MemoryCell> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cells.add(cell("c" + i, "content-" + i));
        }

        MemoryQuery query = MemoryQuery.byText("content", 2);
        RetrievalPipeline.PipelineResult result = pipeline.execute(query, cells, 0);

        // 无 selector 时自动回退到 topK=2
        assertThat(result.finalCells()).hasSize(2);
    }

    @Test
    @DisplayName("maxTokens=0 或不设置 compressor 不压缩")
    void shouldSkipCompressionWhenDisabled() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .retriever(new MockRetriever())
                .ranker(new MockRanker())
                .selector(new MockSelector())
                .compressor(new MockCompressor())
                .build();

        List<MemoryCell> cells = List.of(cell("c1", "hello"));
        MemoryQuery query = MemoryQuery.byText("hello", 5);

        // maxTokens = 0, compressor not called (safeCall returns selected as-is due to maxTokens<=0)
        RetrievalPipeline.PipelineResult result = pipeline.execute(query, cells, 0);
        assertThat(result.finalCells()).isNotEmpty();
    }

    @Test
    @DisplayName("compressor=null 跳过压缩")
    void shouldSkipCompressionWhenNullCompressor() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .retriever(new MockRetriever())
                .selector(new MockSelector())
                .build();

        List<MemoryCell> cells = List.of(cell("c1", "hello"));
        MemoryQuery query = MemoryQuery.byText("hello", 5);

        RetrievalPipeline.PipelineResult result = pipeline.execute(query, cells, 500);
        assertThat(result.finalCells()).isNotEmpty();
    }

    @Test
    @DisplayName("PipelineResult.totalTime 计算总耗时")
    void shouldCalculateTotalTime() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .retriever(new MockRetriever())
                .ranker(new MockRanker())
                .selector(new MockSelector())
                .build();

        List<MemoryCell> cells = List.of(cell("c1", "hello"));
        RetrievalPipeline.PipelineResult result = pipeline.execute(
                MemoryQuery.byText("hello", 5), cells, 0);

        assertThat(result.totalTime().toNanos()).isGreaterThanOrEqualTo(0);
    }

    // Mock implementations

    static class MockRetriever implements MemoryRetriever {
        @Override
        public List<ScoredMemoryCell> retrieve(MemoryQuery query, List<MemoryCell> cells) {
            return cells.stream()
                    .map(c -> ScoredMemoryCell.of(c, 0.9, c.memoryType()))
                    .toList();
        }

        @Override
        public RetrievalStrategy strategy() {
            return RetrievalStrategy.KEYWORD;
        }
    }

    static class MockRanker implements RelevanceRanker {
        @Override
        public double score(MemoryCell cell, MemoryQuery query) {
            return 0.8;
        }
    }

    static class MockSelector implements MemorySelector {
        @Override
        public List<MemoryCell> select(List<ScoredMemoryCell> rankedCells, MemoryQuery query) {
            return rankedCells.stream()
                    .limit(query.topK())
                    .map(ScoredMemoryCell::cell)
                    .toList();
        }

        @Override
        public String name() {
            return "mock";
        }
    }

    static class MockCompressor implements MemoryCompressor {
        @Override
        public List<MemoryCell> compress(List<MemoryCell> cells, CompressionContext ctx) {
            return cells;
        }

        @Override
        public CompressionStrategy strategy() {
            return CompressionStrategy.TRUNCATE;
        }
    }
}
