package com.yin.ai.platform.rag.evaluation;

import com.yin.ai.platform.core.rag.evaluation.RagEvaluationDataset;
import com.yin.ai.platform.core.rag.evaluation.RagEvaluationResult;
import com.yin.ai.platform.rag.enhanced.EnhancedRagService;
import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * RAG 测评服务 — 对 EnhancedRagService 的检索结果执行质量评测。
 *
 * <p>【迁移自 wkc-ai-service】基座能力归属 platform。
 * 对每个评测查询用例，调用 EnhancedRagService.retrieveDocuments 获取检索结果，
 * 用 {@link RagEvaluationMetrics} 计算 MRR/NDCG/Recall@K/Precision@K 指标。
 *
 * <p>对齐规范：07-RAG.md §13（RAG 可观测性）、09-Observability.md §5.5（RAG 层 Metrics）。
 */
@Slf4j
@Service
public class RagEvaluationService {

    private static final int DEFAULT_K = 5;

    private final EnhancedRagService enhancedRagService;

    public RagEvaluationService(EnhancedRagService enhancedRagService) {
        this.enhancedRagService = enhancedRagService;
    }

    public RagEvaluationResult evaluate(RagEvaluationDataset dataset, int k) {
        if (dataset == null || dataset.queries() == null || dataset.queries().isEmpty()) {
            String datasetName = dataset != null ? dataset.name() : "empty";
            String datasetVersion = dataset != null ? dataset.version() : "unversioned";
            return new RagEvaluationResult(datasetName, datasetVersion, "deterministic-ir", "1.1.0",
                    Instant.now(), 0, null, k > 0 ? k : DEFAULT_K, 0, List.of(), Map.of());
        }
        return doEvaluate(dataset, k);
    }

    private RagEvaluationResult doEvaluate(RagEvaluationDataset dataset, int k) {
        int effectiveK = k > 0 ? k : DEFAULT_K;
        List<RagEvaluationResult.QueryResult> results = new ArrayList<>();
        double mrrSum = 0, ndcgSum = 0, recallSum = 0, precisionSum = 0, hitRateSum = 0;
        double recall1Sum = 0, recall3Sum = 0, recall5Sum = 0, recall10Sum = 0;

        for (RagEvaluationDataset.QueryCase qc : dataset.queries()) {
            try {
                RagEvaluationResult.QueryResult qr = evaluateQuery(qc, effectiveK);
                results.add(qr);

                mrrSum += qr.scores().getOrDefault("MRR", 0.0);
                ndcgSum += qr.scores().getOrDefault("NDCG@K", 0.0);
                recallSum += qr.scores().getOrDefault("Recall@K", 0.0);
                precisionSum += qr.scores().getOrDefault("Precision@K", 0.0);
                hitRateSum += qr.scores().getOrDefault("HitRate@K", 0.0);
                recall1Sum += qr.scores().getOrDefault("Recall@1", 0.0);
                recall3Sum += qr.scores().getOrDefault("Recall@3", 0.0);
                recall5Sum += qr.scores().getOrDefault("Recall@5", 0.0);
                recall10Sum += qr.scores().getOrDefault("Recall@10", 0.0);
            } catch (Exception e) {
                log.error("[RAG-EVAL] queryId={} 评测失败: {}", qc.id(), e.getMessage());
            }
        }

        int count = results.size();
        Map<String, Double> aggregate = new LinkedHashMap<>();
        aggregate.put("MRR", count > 0 ? mrrSum / count : 0.0);
        aggregate.put("NDCG@K", count > 0 ? ndcgSum / count : 0.0);
        aggregate.put("Recall@K", count > 0 ? recallSum / count : 0.0);
        aggregate.put("Precision@K", count > 0 ? precisionSum / count : 0.0);
        aggregate.put("HitRate@K", count > 0 ? hitRateSum / count : 0.0);
        aggregate.put("Recall@1", count > 0 ? recall1Sum / count : 0.0);
        aggregate.put("Recall@3", count > 0 ? recall3Sum / count : 0.0);
        aggregate.put("Recall@5", count > 0 ? recall5Sum / count : 0.0);
        aggregate.put("Recall@10", count > 0 ? recall10Sum / count : 0.0);

        log.info("[RAG-EVAL] dataset={} queries={} avgMRR={} avgNDCG={} avgRecall={} avgPrecision={}",
                dataset.name(), count, aggregate.get("MRR"), aggregate.get("NDCG@K"),
                aggregate.get("Recall@K"), aggregate.get("Precision@K"));

        return new RagEvaluationResult(dataset.name(), dataset.version(), "deterministic-ir", "1.1.0",
                Instant.now(), count, null, effectiveK, count, results, aggregate);
    }

    public RagEvaluationResult.QueryResult evaluateQuery(
            RagEvaluationDataset.QueryCase qc, int k) {
        Set<String> relevantDocIds = new HashSet<>(qc.relevantDocIds() != null
                ? qc.relevantDocIds() : List.of());

        // userId 传 null（评测不需要用户审计）
        List<RetrievedDocument> retrieved = enhancedRagService.retrieveDocuments(qc.query(), null);

        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("MRR", RagEvaluationMetrics.mrr(retrieved, relevantDocIds));
        scores.put("NDCG@K", RagEvaluationMetrics.ndcg(retrieved, relevantDocIds, k));
        scores.put("Recall@K", RagEvaluationMetrics.recallAtK(retrieved, relevantDocIds, k));
        scores.put("Precision@K", RagEvaluationMetrics.precisionAtK(retrieved, relevantDocIds, k));
        scores.put("HitRate@K", RagEvaluationMetrics.hitRate(retrieved, relevantDocIds, k));
        scores.put("Recall@1", RagEvaluationMetrics.recallAtK(retrieved, relevantDocIds, 1));
        scores.put("Recall@3", RagEvaluationMetrics.recallAtK(retrieved, relevantDocIds, 3));
        scores.put("Recall@5", RagEvaluationMetrics.recallAtK(retrieved, relevantDocIds, 5));
        scores.put("Recall@10", RagEvaluationMetrics.recallAtK(retrieved, relevantDocIds, 10));

        int relevantRetrieved = (int) retrieved.stream()
                .filter(d -> relevantDocIds.contains(d.docId()))
                .count();

        return new RagEvaluationResult.QueryResult(
                qc.id(), qc.query(), retrieved.size(), relevantRetrieved, scores);
    }

    public List<RetrievedDocument> testRetrieve(String query) {
        return enhancedRagService.retrieveDocuments(query, null);
    }
}
