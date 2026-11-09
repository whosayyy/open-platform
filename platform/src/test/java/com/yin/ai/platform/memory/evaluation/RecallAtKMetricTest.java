package com.yin.ai.platform.memory.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Recall@K Metric")
class RecallAtKMetricTest {

    private final RecallAtKMetric metric = new RecallAtKMetric();

    private static RetrievedDocument doc(int rank, String docId) {
        return new RetrievedDocument(rank, 0.9, "keyword", docId, "title-" + docId,
                "default", "chunk-" + docId, 0, "", 0, 0, "preview", Map.of());
    }

    @Test
    @DisplayName("name is Recall")
    void nameIsRecall() {
        assertThat(metric.name()).isEqualTo("Recall");
    }

    @Test
    @DisplayName("all relevant docs retrieved → 1.0")
    void allRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"), doc(3, "C"));
        double score = metric.compute(docs, List.of("A", "B"), 5);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("half relevant docs retrieved → 0.5")
    void halfRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "X"));
        double score = metric.compute(docs, List.of("A", "B"), 5);
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    @DisplayName("none relevant retrieved → 0.0")
    void noneRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "X"), doc(2, "Y"));
        double score = metric.compute(docs, List.of("A", "B"), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("empty results → 0.0")
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
    @DisplayName("relevant docs beyond k are filtered out")
    void relevantBeyondK() {
        List<RetrievedDocument> docs = List.of(doc(1, "X"), doc(2, "Y"), doc(3, "A"));
        double score = metric.compute(docs, List.of("A"), 1);
        assertThat(score).isEqualTo(0.0);
    }
}
