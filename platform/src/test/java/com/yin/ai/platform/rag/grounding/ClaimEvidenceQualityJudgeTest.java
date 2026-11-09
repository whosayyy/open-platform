package com.yin.ai.platform.rag.grounding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaimEvidenceQualityJudgeTest {

    private final StructuredClient client = mock(StructuredClient.class);
    private final ClaimEvidenceQualityJudge judge =
            new ClaimEvidenceQualityJudge(client, new ObjectMapper());

    @Test
    void acceptsOnlyExplainableEnterpriseQualityThresholds() {
        when(client.isAvailable()).thenReturn(true);
        when(client.generate(anyString(), anyString(), any(), anyString(), isNull(), anyDouble()))
                .thenReturn(Optional.of("""
                        {"faithfulness":0.99,"evidenceSupportRate":0.95,
                         "sourceTrustScore":0.92,"freshnessScore":0.94,
                         "conflictRate":0.01,"unsupportedFactRate":0.01}
                        """));

        ClaimEvidenceQualityJudge.Quality quality = judge.evaluate(
                "A fact is supported by https://example.com/source.",
                List.of(new ClaimEvidenceQualityJudge.EvidenceSource(
                        "Source", "https://example.com/source", "Example",
                        "Evidence for A fact", "2026-08-13")));

        assertThat(quality).isNotNull();
        assertThat(quality.passes()).isTrue();
        assertThat(quality.sourceTrustScore()).isEqualTo(0.92);
        assertThat(quality.freshnessScore()).isEqualTo(0.94);
    }

    @Test
    void rejectsUnsupportedOrConflictingClaimsAtMoreThanTwoPercent() {
        assertThat(new ClaimEvidenceQualityJudge.Quality(
                0.99, 0.95, 0.90, 0.90, 0.01, 0.021).passes()).isFalse();
        assertThat(new ClaimEvidenceQualityJudge.Quality(
                0.99, 0.95, 0.90, 0.90, 0.021, 0.01).passes()).isFalse();
    }

    @Test
    void failsClosedWhenJudgeIsUnavailable() {
        when(client.isAvailable()).thenReturn(false);

        assertThat(judge.evaluate("article", List.of(new ClaimEvidenceQualityJudge.EvidenceSource(
                "Source", "https://example.com", "Example", "Evidence", "2026"))))
                .isNull();
    }
}
