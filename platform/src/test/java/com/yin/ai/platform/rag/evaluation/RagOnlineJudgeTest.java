package com.yin.ai.platform.rag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagOnlineJudgeTest {

    private final StructuredClient structuredClient = mock(StructuredClient.class);
    private RagOnlineJudge judge;

    @BeforeEach
    void setUp() {
        judge = new RagOnlineJudge(structuredClient, new ObjectMapper());
    }

    @Test
    void evaluateParsesScoresAndClampsToUnitInterval() {
        when(structuredClient.isAvailable()).thenReturn(true);
        when(structuredClient.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"faithfulness\":0.85,\"answerRelevance\":1.2}"));

        RagOnlineJudge.Judgment result = judge.evaluate("问题", "回答",
                List.of("上下文1", "上下文2"));

        assertThat(result).isNotNull();
        assertThat(result.faithfulness()).isEqualTo(0.85);
        // 超界值被钳制到 [0,1]
        assertThat(result.answerRelevance()).isEqualTo(1.0);
    }

    @Test
    void evaluateReturnsNullWhenUnavailable() {
        when(structuredClient.isAvailable()).thenReturn(false);

        RagOnlineJudge.Judgment result = judge.evaluate("q", "a", List.of("ctx"));

        assertThat(result).isNull();
        verify(structuredClient, never()).generate(anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void evaluateReturnsNullOnParseFailure() {
        when(structuredClient.isAvailable()).thenReturn(true);
        when(structuredClient.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("not-json"));

        RagOnlineJudge.Judgment result = judge.evaluate("q", "a", List.of("ctx"));

        assertThat(result).isNull();
    }

    @Test
    void evaluateSkipsWithoutContextOrBlankAnswer() {
        when(structuredClient.isAvailable()).thenReturn(true);

        assertThat(judge.evaluate("q", "  ", List.of("ctx"))).isNull();
        assertThat(judge.evaluate("q", "a", List.of())).isNull();
        verify(structuredClient, never()).generate(anyString(), anyString(), any(), anyString(), any(), any());
    }
}
