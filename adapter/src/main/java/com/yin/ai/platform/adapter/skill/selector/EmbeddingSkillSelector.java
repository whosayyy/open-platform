package com.yin.ai.platform.adapter.skill.selector;

import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.core.skill.selector.SkillSelectionResult;
import com.yin.ai.platform.core.skill.selector.SkillSelector;
import com.yin.ai.platform.adapter.router.embedding.EmbeddingMatcher;
import com.yin.ai.platform.core.router.RouteContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于语义嵌入的 Skill 选择器 — 当 {@link IntentSkillSelector} 无法匹配时作为兜底。
 * <p>
 * 启动时加载所有 Skill 清单，预计算每个 Skill 描述文本的 Embedding 向量。
 * 运行时将 Intent 文本向量化，通过 Cosine 相似度匹配最合适的 Skill。
 * <p>
 * EmbeddingModel 不可用时优雅降级，返回默认 "chat" Skill。
 */
@Slf4j
public class EmbeddingSkillSelector implements SkillSelector {

    private static final double DEFAULT_THRESHOLD = 0.7;

    private final SkillLoader skillLoader;
    private final EmbeddingModel embeddingModel;
    private final double threshold;
    private final Map<String, float[]> skillEmbeddings = new HashMap<>();
    private final Map<String, String> skillDescriptions = new HashMap<>();
    private boolean ready;

    public EmbeddingSkillSelector(SkillLoader skillLoader, EmbeddingModel embeddingModel, double threshold) {
        this.skillLoader = skillLoader;
        this.embeddingModel = embeddingModel;
        this.threshold = threshold;
    }

    public EmbeddingSkillSelector(SkillLoader skillLoader, EmbeddingModel embeddingModel) {
        this(skillLoader, embeddingModel, DEFAULT_THRESHOLD);
    }

    private String buildSignature(SkillManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append(manifest.getName());
        if (manifest.getDescription() != null && !manifest.getDescription().isBlank()) {
            sb.append('\n').append(manifest.getDescription());
        }
        if (manifest.getIntent() != null && !manifest.getIntent().isBlank()) {
            sb.append('\n').append(manifest.getIntent());
        }
        if (manifest.getCategory() != null && !manifest.getCategory().isBlank()) {
            sb.append('\n').append(manifest.getCategory());
        }
        if (manifest.getTags() != null && !manifest.getTags().isEmpty()) {
            sb.append('\n').append(String.join(", ", manifest.getTags()));
        }
        return sb.toString();
    }

    @PostConstruct
    void init() {
        if (embeddingModel == null) {
            log.warn("[EMBED-SELECTOR] EmbeddingModel unavailable, semantic skill selection disabled");
            return;
        }

        try {
            for (SkillManifest manifest : skillLoader.loadAll()) {
                String signature = buildSignature(manifest);
                if (signature.isBlank()) {
                    log.debug("[EMBED-SELECTOR] skipping skill '{}' — empty signature", manifest.getName());
                    continue;
                }
                try {
                    Embedding emb = embeddingModel.embed(signature).content();
                    skillEmbeddings.put(manifest.getName(), emb.vector());
                    skillDescriptions.put(manifest.getName(), signature);
                } catch (Exception e) {
                    log.warn("[EMBED-SELECTOR] failed to embed skill '{}'", manifest.getName(), e);
                }
            }
            ready = !skillEmbeddings.isEmpty();
            log.info("[EMBED-SELECTOR] initialized with {} skills, threshold={}", skillEmbeddings.size(), threshold);
        } catch (Exception e) {
            log.error("[EMBED-SELECTOR] init failed", e);
        }
    }

    @Override
    public SkillSelectionResult select(RouteContext context, String intent) {
        if (!ready || intent == null || intent.isBlank()) {
            return fallback();
        }

        try {
            Embedding intentEmb = embeddingModel.embed(intent).content();
            float[] intentVector = intentEmb.vector();

            String bestSkill = null;
            double bestScore = -1.0;

            for (var entry : skillEmbeddings.entrySet()) {
                double score = EmbeddingMatcher.cosineSimilarity(intentVector, entry.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestSkill = entry.getKey();
                }
            }

            if (bestSkill != null && bestScore >= threshold) {
                log.debug("[EMBED-SELECTOR] matched skill='{}' intent='{}' score={}",
                        bestSkill, intent, String.format("%.3f", bestScore));
                return SkillSelectionResult.builder()
                        .skillNames(List.of(bestSkill))
                        .selectorName(selectorName())
                        .confidence(bestScore)
                        .build();
            }

            log.debug("[EMBED-SELECTOR] no skill above threshold, best='{}' score={}",
                    bestSkill, String.format("%.3f", bestScore));
            return fallback();

        } catch (Exception e) {
            log.warn("[EMBED-SELECTOR] match failed, falling back to default", e);
            return fallback();
        }
    }

    private SkillSelectionResult fallback() {
        return SkillSelectionResult.builder()
                .skillNames(List.of("chat"))
                .selectorName(selectorName())
                .confidence(0.5)
                .build();
    }

    @Override
    public String selectorName() {
        return "embedding";
    }

    // package-visible for testing
    boolean isReady() { return ready; }
    Map<String, String> getSkillDescriptions() { return Map.copyOf(skillDescriptions); }
}
