package com.yin.ai.platform.rag.grounding;

import com.yin.ai.platform.core.rag.grounding.ClaimCitationAnalyzer;
import com.yin.ai.platform.rag.enhanced.EnhancedRagProperties;
import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import com.yin.ai.platform.rag.evaluation.RagOnlineJudge;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最终答案证据门禁：先验证引用编号，再使用独立 Judge 校验答案忠诚度。
 * Judge 不可用时由 failClosed 决定是否拒绝，绝不伪造通过分数。
 */
@Component
public class RagGroundingGuard {

    private static final Pattern CITATION = Pattern.compile("\\[E(\\d+)]");
    private static final String REFUSAL =
            "现有检索证据不足以可靠回答这个问题。为避免提供未经证据支持的信息，我暂不作确定性结论。";

    private final RagOnlineJudge judge;
    private final EnhancedRagProperties properties;
    private final MeterRegistry meterRegistry;

    public RagGroundingGuard(RagOnlineJudge judge, EnhancedRagProperties properties) {
        this(judge, properties, (MeterRegistry) null);
    }

    @Autowired
    public RagGroundingGuard(RagOnlineJudge judge, EnhancedRagProperties properties,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(judge, properties, meterRegistryProvider.getIfAvailable());
    }

    private RagGroundingGuard(RagOnlineJudge judge, EnhancedRagProperties properties,
                              MeterRegistry meterRegistry) {
        this.judge = judge;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public boolean enabled() {
        return properties.getGrounding().isEnabled();
    }

    public int maxRegenerationAttempts() {
        return Math.max(0, properties.getGrounding().getMaxRegenerationAttempts());
    }

    public ValidationResult validate(String query, String answer,
                                     List<RetrievedDocument> documents) {
        return validate(query, answer, documents, documents != null && !documents.isEmpty());
    }

    public ValidationResult validate(String query, String answer,
                                     List<RetrievedDocument> documents,
                                     boolean evidenceSufficient) {
        ValidationResult result = doValidate(query, answer, documents, evidenceSufficient);
        recordMetrics(result);
        return result;
    }

    private ValidationResult doValidate(String query, String answer,
                                        List<RetrievedDocument> documents,
                                        boolean evidenceSufficient) {
        if (!enabled()) {
            return ValidationResult.pass(1.0, Set.of());
        }
        if (!evidenceSufficient || documents == null || documents.isEmpty()) {
            return properties.getGrounding().isFailClosed()
                    ? ValidationResult.fail(0.0, "insufficient_retrieved_evidence", Set.of())
                    : ValidationResult.pass(Double.NaN, Set.of());
        }
        if (answer == null || answer.isBlank()) {
            return ValidationResult.fail(0.0, "empty_answer", Set.of());
        }

        Set<Integer> citations = extractCitations(answer);
        if (properties.getGrounding().isRequireCitations() && citations.isEmpty()) {
            return ValidationResult.fail(0.0, "missing_evidence_citation", citations);
        }
        boolean invalidCitation = citations.stream()
                .anyMatch(number -> number < 1 || number > documents.size());
        if (invalidCitation) {
            return ValidationResult.fail(0.0, "unknown_evidence_citation", citations);
        }

        ClaimCitationAnalyzer.Analysis claimAnalysis = ClaimCitationAnalyzer.analyze(answer);
        double minimumCoverage = Math.max(0.0, Math.min(1.0,
                properties.getGrounding().getMinClaimCitationCoverage()));
        if (properties.getGrounding().isRequireClaimLevelCitations()
                && !claimAnalysis.meets(minimumCoverage)) {
            return ValidationResult.fail(0.0, "claim_citation_coverage_below_threshold",
                    citations, claimAnalysis);
        }

        List<String> evidence = documents.stream()
                .map(doc -> (doc.title() != null ? doc.title() + "\n" : "")
                        + (doc.content() != null ? doc.content() : ""))
                .toList();
        RagOnlineJudge.Judgment judgment = judge.evaluate(query, answer, evidence);
        if (judgment == null) {
            return properties.getGrounding().isFailClosed()
                    ? ValidationResult.fail(0.0, "grounding_judge_unavailable", citations, claimAnalysis)
                    : ValidationResult.pass(Double.NaN, citations, claimAnalysis);
        }
        double threshold = Math.max(0.0,
                Math.min(1.0, properties.getGrounding().getMinFaithfulness()));
        if (judgment.faithfulness() < threshold) {
            return ValidationResult.fail(judgment.faithfulness(),
                    "faithfulness_below_threshold", citations, claimAnalysis);
        }
        if (judgment.evidenceSupportRate() < 0.90) {
            return ValidationResult.fail(judgment, "evidence_support_below_threshold",
                    citations, claimAnalysis);
        }
        if (judgment.conflictRate() > 0.02) {
            return ValidationResult.fail(judgment, "source_conflict_rate_above_threshold",
                    citations, claimAnalysis);
        }
        if (judgment.unsupportedFactRate() > 0.02) {
            return ValidationResult.fail(judgment, "unsupported_fact_rate_above_threshold",
                    citations, claimAnalysis);
        }
        return ValidationResult.pass(judgment, citations, claimAnalysis);
    }

    public String refusal() {
        return REFUSAL;
    }

    private Set<Integer> extractCitations(String answer) {
        Matcher matcher = CITATION.matcher(answer);
        Set<Integer> result = new LinkedHashSet<>();
        while (matcher.find()) {
            try {
                result.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Regex 已限制为数字；异常时按非法引用处理即可。
            }
        }
        return Set.copyOf(new ArrayList<>(result));
    }

    private void recordMetrics(ValidationResult result) {
        if (meterRegistry == null || result == null) return;
        meterRegistry.counter("ai.rag.grounding.validation",
                "outcome", result.valid() ? "passed" : "rejected",
                "reason", result.valid() ? "none" : result.reason()).increment();
        if (!Double.isNaN(result.claimCitationCoverage())) {
            DistributionSummary.builder("ai.rag.grounding.claim.citation.coverage")
                    .description("Share of factual claim blocks carrying a local evidence citation")
                    .baseUnit("ratio")
                    .register(meterRegistry)
                    .record(result.claimCitationCoverage());
        }
        recordRatio("ai.rag.grounding.evidence.support", result.evidenceSupportRate());
        recordRatio("ai.rag.grounding.answer.relevance", result.answerRelevance());
        recordRatio("ai.rag.grounding.source.conflict", result.conflictRate());
        recordRatio("ai.rag.grounding.unsupported.fact", result.unsupportedFactRate());
    }

    private void recordRatio(String name, double value) {
        if (Double.isNaN(value)) return;
        DistributionSummary.builder(name).baseUnit("ratio").register(meterRegistry).record(value);
    }

    public record ValidationResult(boolean valid, double faithfulness,
                                   String reason, Set<Integer> citations,
                                   int claimCount, int supportedClaimCount,
                                   double claimCitationCoverage,
                                   double answerRelevance, double evidenceSupportRate,
                                   double conflictRate, double unsupportedFactRate) {
        public ValidationResult {
            reason = reason != null ? reason : "";
            citations = citations != null ? Set.copyOf(citations) : Set.of();
        }

        /** Backward-compatible constructor for callers that do not yet provide claim metrics. */
        public ValidationResult(boolean valid, double faithfulness,
                                String reason, Set<Integer> citations) {
            this(valid, faithfulness, reason, citations, 0, 0, Double.NaN);
        }

        public ValidationResult(boolean valid, double faithfulness, String reason,
                                Set<Integer> citations, int claimCount,
                                int supportedClaimCount, double claimCitationCoverage) {
            this(valid, faithfulness, reason, citations, claimCount, supportedClaimCount,
                    claimCitationCoverage, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        static ValidationResult pass(double faithfulness, Set<Integer> citations) {
            return new ValidationResult(true, faithfulness, "", citations, 0, 0, Double.NaN);
        }

        static ValidationResult pass(double faithfulness, Set<Integer> citations,
                                     ClaimCitationAnalyzer.Analysis analysis) {
            return new ValidationResult(true, faithfulness, "", citations,
                    analysis.claimCount(), analysis.supportedClaimCount(), analysis.coverage());
        }

        static ValidationResult pass(RagOnlineJudge.Judgment judgment, Set<Integer> citations,
                                     ClaimCitationAnalyzer.Analysis analysis) {
            return new ValidationResult(true, judgment.faithfulness(), "", citations,
                    analysis.claimCount(), analysis.supportedClaimCount(), analysis.coverage(),
                    judgment.answerRelevance(), judgment.evidenceSupportRate(),
                    judgment.conflictRate(), judgment.unsupportedFactRate());
        }

        static ValidationResult fail(double faithfulness, String reason,
                                     Set<Integer> citations) {
            return new ValidationResult(false, faithfulness, reason, citations, 0, 0, Double.NaN);
        }

        static ValidationResult fail(double faithfulness, String reason,
                                     Set<Integer> citations,
                                     ClaimCitationAnalyzer.Analysis analysis) {
            return new ValidationResult(false, faithfulness, reason, citations,
                    analysis.claimCount(), analysis.supportedClaimCount(), analysis.coverage());
        }

        static ValidationResult fail(RagOnlineJudge.Judgment judgment, String reason,
                                     Set<Integer> citations,
                                     ClaimCitationAnalyzer.Analysis analysis) {
            return new ValidationResult(false, judgment.faithfulness(), reason, citations,
                    analysis.claimCount(), analysis.supportedClaimCount(), analysis.coverage(),
                    judgment.answerRelevance(), judgment.evidenceSupportRate(),
                    judgment.conflictRate(), judgment.unsupportedFactRate());
        }
    }
}
