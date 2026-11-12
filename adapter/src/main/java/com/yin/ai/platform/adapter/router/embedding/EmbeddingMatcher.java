package com.yin.ai.platform.adapter.router.embedding;

import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.RouteContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 语义意图匹配器 — 基于 Embedding 余弦相似度匹配用户意图。
 * <p>
 * 优先级 40，在 Keyword(100) / Regex(80) / Rule(60) 之后、DefaultMatcher 之前。
 * 意图描述通过 {@code ai.router.embedding-intent-descriptions} 配置。
 * EmbeddingModel 不可用时优雅降级返回 empty。
 */
@Slf4j
public class EmbeddingMatcher implements IntentMatcher {

    private static final double DEFAULT_THRESHOLD = 0.75;

    private final EmbeddingModel embeddingModel;
    private final double threshold;
    private final Map<String, float[]> intentEmbeddings = new HashMap<>();
    private volatile boolean ready;

    public EmbeddingMatcher(EmbeddingModel embeddingModel, Map<String, String> intentDescriptions) {
        this.embeddingModel = embeddingModel;
        this.threshold = DEFAULT_THRESHOLD;
        if (intentDescriptions != null && !intentDescriptions.isEmpty()) {
            initEmbeddings(intentDescriptions);
        }
    }

    // package-visible for testing
    EmbeddingMatcher(EmbeddingModel embeddingModel, double threshold, Map<String, String> intentDescriptions) {
        this.embeddingModel = embeddingModel;
        this.threshold = threshold;
        if (embeddingModel != null && intentDescriptions != null && !intentDescriptions.isEmpty()) {
            initEmbeddings(intentDescriptions);
        }
    }

    // package-visible for testing
    EmbeddingMatcher(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.threshold = DEFAULT_THRESHOLD;
    }

    @PostConstruct
    void init() {
        if (embeddingModel == null) {
            log.warn("[EMBEDDING-MATCHER] EmbeddingModel unavailable, semantic matching disabled");
            return;
        }
        if (!intentEmbeddings.isEmpty()) {
            ready = true;
            log.info("[EMBEDDING-MATCHER] initialized with {} intent embeddings from config, threshold={}",
                    intentEmbeddings.size(), threshold);
        }
    }

    private void initEmbeddings(Map<String, String> descriptions) {
        if (embeddingModel == null) return;
        try {
            // 探针调用：DisabledEmbeddingModel 会立即抛 ModelDisabledException，
            // 避免后续循环中重复打错误日志
            embeddingModel.embed("").content();
        } catch (Exception e) {
            log.warn("[EMBEDDING-MATCHER] EmbeddingModel not usable, semantic matching disabled: {}",
                    e.getMessage());
            return;
        }
        try {
            for (var entry : descriptions.entrySet()) {
                Embedding emb = embeddingModel.embed(entry.getValue()).content();
                intentEmbeddings.put(entry.getKey(), emb.vector());
            }
        } catch (Exception e) {
            log.warn("[EMBEDDING-MATCHER] init from config failed: {}", e.getMessage());
        }
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public Set<String> supportedIntents() {
        return intentEmbeddings.keySet();
    }

    @Override
    public Optional<IntentMatchResult> match(String userInput, RouteContext ctx) {
        if (!ready || embeddingModel == null || userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }

        try {
            float[] queryVector = embeddingModel.embed(userInput).content().vector();

            String bestIntent = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (var entry : intentEmbeddings.entrySet()) {
                double score = cosineSimilarity(queryVector, entry.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestIntent = entry.getKey();
                }
            }

            if (bestIntent != null && bestScore >= threshold) {
                log.debug("[EMBEDDING-MATCHER] matched intent={} score={}", bestIntent, String.format("%.3f", bestScore));
                return Optional.of(IntentMatchResult.builder()
                        .intent(bestIntent)
                        .confidence(bestScore)
                        .matchedBy("embedding")
                        .matchedRule("semantic similarity: " + String.format("%.3f", bestScore))
                        .build());
            }

            log.debug("[EMBEDDING-MATCHER] no match, best={} score={} threshold={}",
                    bestIntent, String.format("%.3f", bestScore), threshold);
            return Optional.empty();

        } catch (Exception e) {
            log.warn("[EMBEDDING-MATCHER] match failed, degrading", e);
            return Optional.empty();
        }
    }

    /** Cosine 相似度计算，结果范围 [-1, 1]。公共方法，供 EmbeddingSkillSelector 等组件使用。 */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
