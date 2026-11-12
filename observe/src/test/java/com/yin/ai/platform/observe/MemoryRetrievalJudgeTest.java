package com.yin.ai.platform.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRetrievalJudgeTest {

    private final StructuredClient structuredClient = mock(StructuredClient.class);
    private MemoryRetrievalJudge judge;

    @BeforeEach
    void setUp() {
        judge = new MemoryRetrievalJudge(structuredClient, new ObjectMapper());
    }

    @Test
    void judgeParsesCellRelevanceJudgments() {
        when(structuredClient.isAvailable()).thenReturn(true);
        when(structuredClient.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"judgments\":[{\"cellId\":\"c1\",\"relevant\":true},"
                        + "{\"cellId\":\"c2\",\"relevant\":false}]}"));

        List<MemoryRetrievalJudge.Judgment> result = judge.judge("查询", cells("c1", "c2"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).cellId()).isEqualTo("c1");
        assertThat(result.get(0).relevant()).isTrue();
        assertThat(result.get(1).relevant()).isFalse();
    }

    @Test
    void judgeReturnsEmptyOnFailure() {
        when(structuredClient.isAvailable()).thenReturn(true);
        when(structuredClient.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("garbage"));

        assertThat(judge.judge("q", cells("c1"))).isEmpty();
    }

    @Test
    void judgeReturnsEmptyWhenUnavailableOrNoCells() {
        when(structuredClient.isAvailable()).thenReturn(false);
        assertThat(judge.judge("q", cells("c1"))).isEmpty();

        when(structuredClient.isAvailable()).thenReturn(true);
        assertThat(judge.judge("q", List.of())).isEmpty();
    }

    private List<ScoredMemoryCell> cells(String... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> ScoredMemoryCell.of(
                        MemoryCell.of(MemoryCellId.of(id), MemoryType.EPISODIC, "记忆内容-" + id),
                        0.8, MemoryType.EPISODIC))
                .toList();
    }
}
