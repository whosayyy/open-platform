package com.yin.ai.platform.rag.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Independent claim-to-evidence judge for web-researched final artifacts. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimEvidenceQualityJudge {

    public static final double MIN_FAITHFULNESS = 0.98;
    public static final double MIN_EVIDENCE_SUPPORT = 0.90;
    public static final double MAX_UNSUPPORTED_FACT_RATE = 0.02;
    public static final double MAX_CONFLICT_RATE = 0.02;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "faithfulness", Map.of("type", "number"),
                    "evidenceSupportRate", Map.of("type", "number"),
                    "sourceTrustScore", Map.of("type", "number"),
                    "freshnessScore", Map.of("type", "number"),
                    "conflictRate", Map.of("type", "number"),
                    "unsupportedFactRate", Map.of("type", "number")),
            "required", List.of("faithfulness", "evidenceSupportRate", "sourceTrustScore",
                    "freshnessScore", "conflictRate", "unsupportedFactRate"));

    private final StructuredClient structuredClient;
    private final ObjectMapper objectMapper;

    public Quality evaluate(String article, List<EvidenceSource> evidence) {
        if (article == null || article.isBlank() || evidence == null || evidence.isEmpty()
                || !structuredClient.isAvailable()) return null;
        try {
            StringBuilder sources = new StringBuilder();
            for (int i = 0; i < Math.min(20, evidence.size()); i++) {
                EvidenceSource source = evidence.get(i);
                sources.append("[S").append(i + 1).append("] ")
                        .append(source.title()).append("\nURL: ").append(source.url())
                        .append("\n媒体: ").append(source.source())
                        .append("\n时间: ").append(source.publishedDate())
                        .append("\n证据: ").append(truncate(source.snippet(), 800)).append("\n\n");
            }
            String prompt = """
                    你是独立的事实核验员。把文章拆成最小事实 claim，逐条检查其就近引用的 URL
                    是否对应给定证据，并判断证据是否直接支持该 claim，不能只看文本相似度。
                    同时识别来源冲突、过期信息、单一来源结论和无法验证的事实。
                    返回 0-1 指标：faithfulness、evidenceSupportRate、sourceTrustScore、
                    freshnessScore、conflictRate、unsupportedFactRate。没有证据支持的事实必须计入
                    unsupportedFactRate；证据与 claim 相反必须计入 conflictRate。不得因写有 URL 就判定支持。

                    已验证证据：
                    %s

                    待核验文章：
                    %s
                    """.formatted(sources, truncate(article, 14000));
            String args = structuredClient.generate("submitClaimEvidenceQuality",
                    "提交 Claim 级证据质量指标，所有字段均为 0-1。", SCHEMA, prompt, null, 0.0)
                    .orElse(null);
            if (args == null) return null;
            JsonNode root = objectMapper.readTree(args);
            Quality quality = new Quality(
                    ratio(root, "faithfulness"), ratio(root, "evidenceSupportRate"),
                    ratio(root, "sourceTrustScore"), ratio(root, "freshnessScore"),
                    ratio(root, "conflictRate"), ratio(root, "unsupportedFactRate"));
            record(quality);
            return quality;
        } catch (Exception e) {
            log.warn("[CLAIM-EVIDENCE-JUDGE] evaluation unavailable: {}", e.getMessage());
            return null;
        }
    }

    private double ratio(JsonNode root, String field) {
        double value = root.path(field).asDouble(Double.NaN);
        if (Double.isNaN(value)) throw new IllegalArgumentException("missing " + field);
        return Math.max(0.0, Math.min(1.0, value));
    }

    private void record(Quality quality) {
        Metrics.summary("ai.agent.final.claim.evidence.support").record(quality.evidenceSupportRate());
        Metrics.summary("ai.agent.final.claim.unsupported").record(quality.unsupportedFactRate());
        Metrics.summary("ai.agent.final.claim.conflict").record(quality.conflictRate());
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record EvidenceSource(String title, String url, String source,
                                 String snippet, String publishedDate) {
        public EvidenceSource {
            title = title == null ? "" : title;
            url = url == null ? "" : url;
            source = source == null ? "" : source;
            snippet = snippet == null ? "" : snippet;
            publishedDate = publishedDate == null ? "" : publishedDate;
        }
    }

    public record Quality(double faithfulness, double evidenceSupportRate,
                          double sourceTrustScore, double freshnessScore,
                          double conflictRate, double unsupportedFactRate) {
        public boolean passes() {
            return faithfulness + 1.0e-9 >= MIN_FAITHFULNESS
                    && evidenceSupportRate + 1.0e-9 >= MIN_EVIDENCE_SUPPORT
                    && conflictRate <= MAX_CONFLICT_RATE + 1.0e-9
                    && unsupportedFactRate <= MAX_UNSUPPORTED_FACT_RATE + 1.0e-9;
        }
    }
}
