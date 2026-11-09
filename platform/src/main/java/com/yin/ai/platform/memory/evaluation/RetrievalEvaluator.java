package com.yin.ai.platform.memory.evaluation;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.memory.retrieval.pipeline.RetrievalPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索评测引擎 — 用标准指标评估检索质量。
 * <p>
 * 评测流程：对每个查询 → 执行检索管道 → 收集溯源 → 计算指标 → 汇总报告。
 */
public class RetrievalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluator.class);

    private final RetrievalPipeline pipeline;
    private final RetrievalTraceCollector traceCollector;
    private final List<EvaluationMetric> metrics;
    private final int defaultK;

    public RetrievalEvaluator(RetrievalPipeline pipeline,
                              RetrievalTraceCollector traceCollector,
                              int defaultK) {
        this.pipeline = pipeline;
        this.traceCollector = traceCollector;
        this.defaultK = defaultK;
        this.metrics = List.of(
                new MrrMetric(),
                new NdcgMetric(),
                new RecallAtKMetric(),
                new PrecisionAtKMetric());
    }

    public RetrievalEvaluator(RetrievalPipeline pipeline, int defaultK) {
        this(pipeline, new RetrievalTraceCollector(), defaultK);
    }

    /**
     * 对数据集执行评测。
     */
    public EvaluationResult evaluate(EvaluationDataset dataset, List<MemoryCell> cells) {
        List<EvaluationResult.QueryResult> results = new ArrayList<>();

        for (EvaluationDataset.QueryCase qc : dataset.queries()) {
            try {
                EvaluationResult.QueryResult qr = evaluateQuery(qc, cells,
                        dataset.knowledgeBase(), defaultK);
                results.add(qr);
            } catch (Exception e) {
                log.error("[EVAL] queryId={} failed: {}", qc.id(), e.getMessage());
            }
        }

        return EvaluationResult.of(dataset.name(), dataset.knowledgeBase(), results);
    }

    private EvaluationResult.QueryResult evaluateQuery(
            EvaluationDataset.QueryCase qc, List<MemoryCell> cells,
            String knowledgeBase, int k) {

        String traceId = UUID.randomUUID().toString();
        Instant start = Instant.now();

        MemoryQuery query = MemoryQuery.byText(qc.query(), k);
        RetrievalPipeline.PipelineResult pipeResult = pipeline.execute(query, cells, 0);

        List<RetrievedDocument> docs = new ArrayList<>();
        int rank = 1;
        for (MemoryCell cell : pipeResult.finalCells()) {
            docs.add(new RetrievedDocument(
                    rank++,
                    0.0,
                    "keyword",
                    cell.cellId().id(),
                    cell.content().length() > 50 ? cell.content().substring(0, 50) : cell.content(),
                    knowledgeBase,
                    cell.cellId().id(),
                    0,
                    "",
                    0L, 0L,
                    safeTruncate(cell.content(), 200),
                    Map.copyOf(cell.attributes())));
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        for (EvaluationMetric metric : metrics) {
            scores.put(metric.name(), metric.compute(docs, qc.relevantDocIds(), k));
        }

        RetrievalTrace trace = new RetrievalTrace(
                traceId, qc.query(), knowledgeBase, Instant.now(),
                Duration.between(start, Instant.now()).toMillis(),
                "keyword", k, 0.0, docs, qc.relevantDocIds(), scores);

        traceCollector.record(trace);

        return new EvaluationResult.QueryResult(qc.id(), qc.query(), scores, trace);
    }

    public RetrievalTraceCollector getTraceCollector() {
        return traceCollector;
    }

    private String safeTruncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
