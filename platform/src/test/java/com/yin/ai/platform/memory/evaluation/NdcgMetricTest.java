package com.yin.ai.platform.memory.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NDCG Metric")
class NdcgMetricTest {

    private final NdcgMetric metric = new NdcgMetric();

    private static RetrievedDocument doc(int rank, String docId) {
        return new RetrievedDocument(rank, 0.9, "keyword", docId, "title-" + docId,
                "default", "chunk-" + docId, 0, "", 0, 0, "preview", Map.of());
    }

    @Test
    @DisplayName("name is NDCG")
    void nameIsNdcg() {
        assertThat(metric.name()).isEqualTo("NDCG");
    }

    @Test
    @DisplayName("perfect ordering → ~1.0")
    void perfectOrdering() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"), doc(3, "C"));
        double score = metric.compute(docs, List.of("A", "B", "C"), 3);
        assertThat(score).isGreaterThan(0.99);
    }

    @Test
    @DisplayName("relevant docs ranked lower → score < 1.0")
    void suboptimalOrdering() {
        List<RetrievedDocument> docs = List.of(doc(1, "X"), doc(2, "A"), doc(3, "B"));
        double score = metric.compute(docs, List.of("A", "B"), 3);
        assertThat(score).isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    @DisplayName("no relevant documents → 0.0")
    void noneRelevant() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"));
        double score = metric.compute(docs, List.of("Z"), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("empty results → 0.0")
    void emptyResults() {
        double score = metric.compute(List.of(), List.of("A", "B"), 5);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("more relevant docs than k → capped")
    void moreRelevantThanK() {
        List<RetrievedDocument> docs = List.of(doc(1, "A"), doc(2, "B"));
        double score = metric.compute(docs, List.of("A", "B", "C"), 2);
        assertThat(score).isGreaterThan(0.99);
    }

    @Test
    @DisplayName("all relevant docs beyond k → 0.0")
    void allRelevantBeyondK() {
        List<RetrievedDocument> docs = List.of(doc(1, "X"), doc(2, "Y"), doc(3, "A"));
        double score = metric.compute(docs, List.of("A"), 1);
        assertThat(score).isEqualTo(0.0);
    }
}
