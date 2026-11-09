package com.yin.ai.platform.agent.pattern.context;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.yin.ai.platform.gateway.GatewayTextClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextManagerMemoryTest {

    private final MemoryRuntime memoryRuntime = mock(MemoryRuntime.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<OnlineQualityRecorder> recorderProvider = mock(ObjectProvider.class);
    private final OnlineQualityRecorder recorder = mock(OnlineQualityRecorder.class);
    private final AgentExecutionContext ctx = mock(AgentExecutionContext.class);
    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        contextManager = new ContextManager(mock(GatewayTextClient.class), 8000, memoryRuntime, recorderProvider);
        when(ctx.getGoal()).thenReturn("分析本周热点");
        when(ctx.getVariable("userId")).thenReturn(7L);
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
    }

    @Test
    void resolveMemoryRecallsAndFormatsMemories() {
        ScoredMemoryCell cell = ScoredMemoryCell.of(
                MemoryCell.of(MemoryCellId.of("c1"), MemoryType.SEMANTIC, "用户偏好科技新闻"),
                12.5, MemoryType.SEMANTIC);
        when(memoryRuntime.retrieve(any(MemoryQuery.class), any()))
                .thenReturn(new QueryResult(List.of(cell), 1, Duration.ZERO, Map.of()));

        InputBinding binding = InputBinding.memory("memoryContext", MemoryType.SEMANTIC, "{goal}", 5);
        Map<String, String> resolved = contextManager.resolve(new StepContract("s1",
                List.of(binding), "out"), ctx);

        assertThat(resolved.get("memoryContext")).contains("历史记忆");
        assertThat(resolved.get("memoryContext")).contains("用户偏好科技新闻");
        // 召回结果写入 ctx 变量（与 ReAct 一致）
        verify(ctx).putVariable(eq("agent.recallResult"), any(QueryResult.class));
        // 在线记忆召回指标
        verify(recorder).recordMemoryRecall(eq(7L), eq("分析本周热点"), any(QueryResult.class));
        ArgumentCaptor<MemoryQuery> queryCaptor = ArgumentCaptor.forClass(MemoryQuery.class);
        verify(memoryRuntime).retrieve(queryCaptor.capture(), any());
        assertThat(queryCaptor.getValue().searchTypes()).containsExactly(MemoryType.SEMANTIC);
    }

    @Test
    void resolveMemoryReturnsEmptyWhenRuntimeFails() {
        when(memoryRuntime.retrieve(any(MemoryQuery.class), any()))
                .thenThrow(new IllegalStateException("es down"));

        InputBinding binding = InputBinding.memory("memoryContext", MemoryType.SEMANTIC, "{goal}", 5);
        Map<String, String> resolved = contextManager.resolve(new StepContract("s1",
                List.of(binding), "out"), ctx);

        // 失败不破坏步骤：绑定值为空串
        assertThat(resolved.get("memoryContext")).isNull();
    }

    @Test
    void resolveMemoryWithoutRuntimeReturnsEmpty() {
        ContextManager noMemory = new ContextManager(mock(GatewayTextClient.class));

        InputBinding binding = InputBinding.memory("memoryContext", MemoryType.SEMANTIC, "{goal}", 5);
        Map<String, String> resolved = noMemory.resolve(new StepContract("s1",
                List.of(binding), "out"), ctx);

        assertThat(resolved.get("memoryContext")).isNull();
    }

    @Test
    void resolveMemoryHonorsBudgetTruncation() {
        ScoredMemoryCell cell = ScoredMemoryCell.of(
                MemoryCell.of(MemoryCellId.of("c1"), MemoryType.SEMANTIC,
                        "很长很长".repeat(300)),
                12.5, MemoryType.SEMANTIC);
        when(memoryRuntime.retrieve(any(MemoryQuery.class), any()))
                .thenReturn(new QueryResult(List.of(cell), 1, Duration.ZERO, Map.of()));

        ContextManager smallBudget = new ContextManager(mock(GatewayTextClient.class), 4, memoryRuntime, recorderProvider);
        InputBinding binding = InputBinding.memory("memoryContext", MemoryType.SEMANTIC, "{goal}", 5);
        Map<String, String> resolved = smallBudget.resolve(new StepContract("s1",
                List.of(binding), "out"), ctx);

        // 超预算的记忆行被跳过（引导文案保留，不注入超长记忆）
        assertThat(resolved.get("memoryContext")).isNotNull();
        assertThat(resolved.get("memoryContext")).doesNotContain("很长很长");
    }
}
