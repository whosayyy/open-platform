package com.yin.ai.platform.memory.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MRR Metric")
class MrrMetricTest {

    private final MrrMetric metric = new MrrMetric();

    private static RetrievedDocument doc(int rank, String docId) {
        return new RetrievedDocument(rank, 0.9, "keyword", docId, "title-" + docId,
                "default", "chunk-" + docId, 0, "", 0, 0, "preview", Map.of());
    }

    @Test
    @DisplayName("name is MRR")
    void nameIsMrr() {
        assertThat(metric.name()).isEqualTo("MRR");
    }

    @Test
    @DisplayName("higherIsBetter returns true")
    void higherIsBetter() {
        assertThat(metric.higherIsBetter()).isTrue();
    }

    @Test
    @DisplayName("first result relevant → score 1.0")
    void firstRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"), doc(3, "C"));
        double score = metric.compute(docs, List.of("A"), 5);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("second result relevant → score 0.5")
    void secondRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"), doc(3, "C"));
        double score = metric.compute(docs, List.of("B"), 5);
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    @DisplayName("no relevant documents found → 0.0")
    void noneRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"));
        double score = metric.compute(docs, List.of("Z"), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("empty returned list → 0.0")
    void emptyResults() {
        double score = metric.compute(List.of(), List.of("A"), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("empty relevant list → 0.0")
    void emptyRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"));
        double score = metric.compute(docs, List.of(), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("relevant doc beyond k is ignored")
    void relevantBeyondK() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"), doc(3, "C"));
        double score = metric.compute(docs, List.of("C"), 1);
        assertThat(score).isEqualTo(0.0);
    }
}
