package com.yin.ai.platform.rag.enhanced;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRerankerTest {

    private List<RetrievedDocument> candidates() {
        return List.of(
                new RetrievedDocument("d1", "one", "first", 0.5, "rrf", Map.of()),
                new RetrievedDocument("d2", "two", "second", 0.4, "rrf", Map.of()),
                new RetrievedDocument("d3", "three", "third", 0.3, "rrf", Map.of()));
    }

    @Test
    void unavailableProviderKeepsRrfOrderAndTopK() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.isAvailable()).thenReturn(false);
        List<RetrievedDocument> result = new LlmReranker(client, new ObjectMapper())
                .rerank("query", candidates(), 2, 1L);
        assertThat(result).extracting(RetrievedDocument::docId).containsExactly("d1", "d2");
    }

    @Test
    void structuredScoresReorderDocuments() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"scores\":[1.0,3.0,2.0]}"));
        List<RetrievedDocument> result = new LlmReranker(client, new ObjectMapper())
                .rerank("query", candidates(), 3, 1L);
        assertThat(result).extracting(RetrievedDocument::docId).containsExactly("d2", "d3", "d1");
        assertThat(result.getFirst().source()).isEqualTo("rerank");
    }

    @Test
    void invalidScoresFallBackToOriginalOrder() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"scores\":\"bad\"}"));
        List<RetrievedDocument> result = new LlmReranker(client, new ObjectMapper())
                .rerank("query", candidates(), 3, 1L);
        assertThat(result).extracting(RetrievedDocument::docId).containsExactly("d1", "d2", "d3");
    }
}
