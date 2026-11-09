package com.yin.ai.platform.memory.retrieval.pipeline;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.memory.compression.CompressionContext;
import com.yin.ai.platform.memory.compression.MemoryCompressor;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.memory.retrieval.MemoryRetriever;
import com.yin.ai.platform.memory.retrieval.RelevanceRanker;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 检索管道 — 编排完整的"召回→排序→精选→压缩"流水线。
 * <p>
 * 管道阶段：
 * <ol>
 *   <li><b>Retrieve（召回）</b>: MemoryRetriever 从全部 Cell 中召回候选集（~200 条）</li>
 *   <li><b>Rank（排序）</b>: RelevanceRanker 对候选集评分排序</li>
 *   <li><b>Select（精选）</b>: MemorySelector 按策略精选（去重、配额、topK）</li>
 *   <li><b>Compress（压缩）</b>: MemoryCompressor 在 Token 预算内压缩</li>
 * </ol>
 * <p>
 * 每个阶段独立可替换。任选编阶段失败时走降级路径（跳过该阶段或返回空列表），
 * 不抛出异常 — 检索失败不应阻塞 LLM 调用。
 * <p>
 * 使用示例：
 * <pre>{@code
 *   RetrievalPipeline pipeline = RetrievalPipeline.builder()
 *       .retriever(hybridRetriever)
 *       .ranker(recencyRanker)
 *       .selector(new TopKSelector())
 *       .compressor(new TruncationCompressor())
 *       .build();
 *   List<MemoryCell> context = pipeline.execute(query, allCells);
 * }</pre>
 */
public class RetrievalPipeline {

    private final MemoryRetriever retriever;
    private final RelevanceRanker ranker;
    private final MemorySelector selector;
    private final MemoryCompressor compressor;

    public RetrievalPipeline(MemoryRetriever retriever, RelevanceRanker ranker,
                              MemorySelector selector, MemoryCompressor compressor) {
        this.retriever = retriever;
        this.ranker = ranker;
        this.selector = selector;
        this.compressor = compressor;
    }

    /**
     * 执行完整检索管道。
     *
     * @param query     检索查询
     * @param cells     全部候选 Cell
     * @param maxTokens 压缩阶段的 Token 预算（0 = 不压缩）
     * @return 管道结果（精选后的 Cell + 耗时统计）
     */
    public PipelineResult execute(MemoryQuery query, List<MemoryCell> cells, int maxTokens) {
        Instant start = Instant.now();

        // Stage 1: Retrieve
        List<ScoredMemoryCell> recalled = safeCall("retrieve",
                () -> retriever != null ? retriever.retrieve(query, cells) : Collections.emptyList());
        Duration retrieveTime = Duration.between(start, Instant.now());

        // Stage 2: Rank
        Instant rankStart = Instant.now();
        List<ScoredMemoryCell> ranked = safeCall("rank", () -> {
            if (ranker == null || recalled.isEmpty()) return recalled;
            return recalled.stream()
                    .map(sc -> {
                        double newScore = ranker.score(sc.cell(), query);
                        return new ScoredMemoryCell(sc.cell(), newScore, sc.sourceType(), sc.explanation());
                    })
                    .sorted(Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                    .toList();
        });
        Duration rankTime = Duration.between(rankStart, Instant.now());

        // Stage 3: Select
        Instant selectStart = Instant.now();
        List<MemoryCell> selected = safeCall("select",
                () -> selector != null ? selector.select(ranked, query) : toCells(ranked, query.topK()));
        Duration selectTime = Duration.between(selectStart, Instant.now());

        // Stage 4: Compress
        Instant compressStart = Instant.now();
        List<MemoryCell> compressed = safeCall("compress", () -> {
            if (compressor == null || maxTokens <= 0 || selected.isEmpty()) return selected;
            return compressor.compress(selected,
                    CompressionContext.of(maxTokens, null));
        });
        Duration compressTime = Duration.between(compressStart, Instant.now());

        return new PipelineResult(compressed, recalled.size(), ranked.size(), selected.size(),
                recalled, retrieveTime, rankTime, selectTime, compressTime);
    }

    private static List<MemoryCell> toCells(List<ScoredMemoryCell> scored, int limit) {
        return scored.stream()
                .limit(limit > 0 ? limit : 10)
                .map(ScoredMemoryCell::cell)
                .toList();
    }

    private static <T> T safeCall(String stage, java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (Exception e) {
            // 单阶段失败不阻塞整体流程
            return (T) Collections.emptyList();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private MemoryRetriever retriever;
        private RelevanceRanker ranker;
        private MemorySelector selector;
        private MemoryCompressor compressor;

        public Builder retriever(MemoryRetriever r) { this.retriever = r; return this; }
        public Builder ranker(RelevanceRanker r) { this.ranker = r; return this; }
        public Builder selector(MemorySelector s) { this.selector = s; return this; }
        public Builder compressor(MemoryCompressor c) { this.compressor = c; return this; }

        public RetrievalPipeline build() {
            return new RetrievalPipeline(retriever, ranker, selector, compressor);
        }
    }

    /**
     * 管道执行结果 — 包含每阶段的统计信息，供可观测性使用。
     */
    public record PipelineResult(
            List<MemoryCell> finalCells,
            int recalledCount,
            int rankedCount,
            int selectedCount,
            List<ScoredMemoryCell> recalled,
            Duration retrieveTime,
            Duration rankTime,
            Duration selectTime,
            Duration compressTime) {

        public Duration totalTime() {
            return retrieveTime.plus(rankTime).plus(selectTime).plus(compressTime);
        }
    }
}
