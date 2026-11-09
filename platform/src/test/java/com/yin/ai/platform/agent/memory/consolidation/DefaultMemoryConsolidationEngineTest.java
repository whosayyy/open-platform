package com.yin.ai.platform.agent.memory.consolidation;

import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategy.ConsolidationPolicy;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.AgentMode;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMemoryConsolidationEngineTest {

    private MemoryRuntime memoryRuntime;
    private AgentExecutionContext context;

    @BeforeEach
    void setUp() {
        memoryRuntime = mock(MemoryRuntime.class);
        context = new AgentExecutionContext("t1", "s1", "u1", AgentMode.REACT,
                null, mock(EventPublisher.class), mock(ContextStore.class));
        context.putVariable("goal", "analyze market");
    }

    @Test
    void fullPolicyExtractsAndPersistsSemanticAndProcedureMemory() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> "submitSemanticFacts".equals(invocation.getArgument(0))
                        ? Optional.of("{\"facts\":[\"prefers technology\",\"tracks AI\"]}")
                        : Optional.of("{\"goalCategory\":\"research\",\"steps\":[\"search\",\"analyze\",\"report\"]}"));

        ConsolidationResult result = new DefaultMemoryConsolidationEngine(memoryRuntime, client)
                .consolidate(context, ConsolidationPolicy.full(3), timeline(3));

        assertThat(result.semanticCells()).hasSize(2);
        assertThat(result.proceduralCells()).hasSize(1);
        assertThat(result.proceduralCells().getFirst().memoryType()).isEqualTo(MemoryType.PROCEDURAL);
        verify(memoryRuntime).appendBatch(any(), any(), any());
    }

    @Test
    void insufficientTimelineAndEpisodicPolicySkipExtraction() {
        StructuredClient client = mock(StructuredClient.class);
        DefaultMemoryConsolidationEngine engine = new DefaultMemoryConsolidationEngine(memoryRuntime, client);
        assertThat(engine.consolidate(context, ConsolidationPolicy.full(10), timeline(3)).semanticCells())
                .isEmpty();
        assertThat(engine.consolidate(context, ConsolidationPolicy.episodicOnly(), timeline(3)).proceduralCells())
                .isEmpty();
        verify(client, never()).generate(anyString(), anyString(), any(), anyString(), any(), any());
        verify(memoryRuntime, never()).appendBatch(any(), any(), any());
    }

    private List<MemoryCell> timeline(int size) {
        List<MemoryCell> cells = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            cells.add(MemoryCell.of(MemoryCellId.of("c" + i), MemoryType.EPISODIC, "step " + i));
        }
        return cells;
    }
}
