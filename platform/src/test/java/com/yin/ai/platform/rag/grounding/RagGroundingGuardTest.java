package com.yin.ai.platform.rag.grounding;

import com.yin.ai.platform.rag.enhanced.EnhancedRagProperties;
import com.yin.ai.platform.core.rag.RetrievedDocument;
import com.yin.ai.platform.rag.evaluation.RagOnlineJudge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagGroundingGuardTest {

    private RagOnlineJudge judge;
    private RagGroundingGuard guard;
    private List<RetrievedDocument> documents;

    @BeforeEach
    void setUp() {
        judge = mock(RagOnlineJudge.class);
        EnhancedRagProperties properties = new EnhancedRagProperties();
        guard = new RagGroundingGuard(judge, properties);
        documents = List.of(new RetrievedDocument(
                "d1", "来源一", "公司营收为100亿元", 9.0, "rerank", Map.of()));
    }

    @Test
    void rejectsAnswerWithoutCitationBeforeCallingJudge() {
        var result = guard.validate("营收多少", "公司营收为100亿元。", documents);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("missing_evidence_citation");
        verify(judge, never()).evaluate(any(), any(), any());
    }

    @Test
    void failsClosedWhenRetrievalHasNoSufficientEvidence() {
        var result = guard.validate("营收多少", "公司营收为100亿元。[E1]",
                documents, false);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("insufficient_retrieved_evidence");
        verify(judge, never()).evaluate(any(), any(), any());
    }

    @Test
    void rejectsUnknownEvidenceNumber() {
        var result = guard.validate("营收多少", "公司营收为100亿元。[E2]", documents);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("unknown_evidence_citation");
    }

    @Test
    void passesOnlyWhenCitationAndFaithfulnessMeetThreshold() {
        when(judge.evaluate(any(), any(), any()))
                .thenReturn(new RagOnlineJudge.Judgment(0.99, 1.0));

        var result = guard.validate("营收多少", "公司营收为100亿元。[E1]", documents);

        assertThat(result.valid()).isTrue();
        assertThat(result.faithfulness()).isEqualTo(0.99);
        assertThat(result.citations()).containsExactly(1);
    }

    @Test
    void failsClosedWhenJudgeUnavailable() {
        when(judge.evaluate(any(), any(), any())).thenReturn(null);

        var result = guard.validate("营收多少", "公司营收为100亿元。[E1]", documents);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("grounding_judge_unavailable");
    }

    @Test
    void rejectsWhenClaimSupportOrHallucinationRiskMissesEnterpriseThreshold() {
        when(judge.evaluate(any(), any(), any()))
                .thenReturn(new RagOnlineJudge.Judgment(0.99, 0.96, 0.89, 0.0, 0.01));
        var lowSupport = guard.validate("营收多少", "公司营收为100亿元。[E1]", documents);
        assertThat(lowSupport.valid()).isFalse();
        assertThat(lowSupport.reason()).isEqualTo("evidence_support_below_threshold");

        when(judge.evaluate(any(), any(), any()))
                .thenReturn(new RagOnlineJudge.Judgment(0.99, 0.96, 0.95, 0.0, 0.03));
        var hallucinationRisk = guard.validate("营收多少", "公司营收为100亿元。[E1]", documents);
        assertThat(hallucinationRisk.valid()).isFalse();
        assertThat(hallucinationRisk.reason()).isEqualTo("unsupported_fact_rate_above_threshold");

        when(judge.evaluate(any(), any(), any()))
                .thenReturn(new RagOnlineJudge.Judgment(0.99, 0.96, 0.95, 0.03, 0.01));
        var conflictRisk = guard.validate("revenue", "Revenue was 10 billion. [E1]", documents);
        assertThat(conflictRisk.valid()).isFalse();
        assertThat(conflictRisk.reason()).isEqualTo("source_conflict_rate_above_threshold");
    }

    @Test
    void exposesExplainableClaimQualityMetrics() {
        when(judge.evaluate(any(), any(), any()))
                .thenReturn(new RagOnlineJudge.Judgment(0.99, 0.97, 0.96, 0.01, 0.01));

        var result = guard.validate("营收多少", "公司营收为100亿元。[E1]", documents);

        assertThat(result.valid()).isTrue();
        assertThat(result.answerRelevance()).isEqualTo(0.97);
        assertThat(result.evidenceSupportRate()).isEqualTo(0.96);
        assertThat(result.conflictRate()).isEqualTo(0.01);
        assertThat(result.unsupportedFactRate()).isEqualTo(0.01);
    }

    @Test
    void rejectsWhenOnlySomeFactualClaimsCarryLocalEvidence() {
        String answer = """
                公司 2025 年营收达到 100 亿元。[E1]

                公司 2026 年将在海外新增三座工厂。
                """;

        var result = guard.validate("公司经营情况", answer, documents);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("claim_citation_coverage_below_threshold");
        assertThat(result.claimCount()).isEqualTo(2);
        assertThat(result.supportedClaimCount()).isEqualTo(1);
        assertThat(result.claimCitationCoverage()).isEqualTo(0.5);
        verify(judge, never()).evaluate(any(), any(), any());
    }
}
