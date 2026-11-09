package com.yin.ai.platform.memory.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Precision@K Metric")
class PrecisionAtKMetricTest {

    private final PrecisionAtKMetric metric = new PrecisionAtKMetric();

    private static RetrievedDocument doc(int rank, String docId) {
        return new RetrievedDocument(rank, 0.9, "keyword", docId, "title-" + docId,
                "default", "chunk-" + docId, 0, "", 0, 0, "preview", Map.of());
    }

    @Test
    @DisplayName("name is Precision")
    void nameIsPrecision() {
        assertThat(metric.name()).isEqualTo("Precision");
    }

    @Test
    @DisplayName("all K results relevant → 1.0")
    void allRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"), doc(3, "C"));
        double score = metric.compute(docs, List.of("A", "B", "C"), 3);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("half of K results relevant → 0.5")
    void halfRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "X"), doc(3, "B"), doc(4, "Y"));
        double score = metric.compute(docs, List.of("A", "B", "C"), 4);
        assertThat(score).isGreaterThan(0.49).isLessThan(0.51);
    }

    @Test
    @DisplayName("none relevant within K → 0.0")
    void noneRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "X"), doc(2, "Y"), doc(3, "Z"));
        double score = metric.compute(docs, List.of("A"), 3);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("empty results → 0.0")
    void emptyResults() {
        double score = metric.compute(List.of(), List.of("A"), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("retrieved fewer than K → denominator is K")
    void lessThanK() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"));
        double score = metric.compute(docs, List.of("A", "B"), 3);
        assertThat(score).isEqualTo(1.0 / 3.0);
    }
}
