package com.yin.ai.platform.agent.memory;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.agent.observer.Observation.ObservationStatus;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.reflector.Decision;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemorySnapshot;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemorySpan;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.PlanNode;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentMemoryBridge")
class AgentMemoryBridgeTest {

    private MemoryRuntime memoryRuntime;
    private AgentMemoryBridge bridge;
    private AgentContext ctx;
    private WorkingMemory wm;

    @BeforeEach
    void setUp() {
        memoryRuntime = mock(MemoryRuntime.class);
        bridge = new AgentMemoryBridge(memoryRuntime, AgentMemoryConfig.defaults());

        ExecutionContext execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), "conv-1", 100L, null, null,
                Map.of(), null, "test", Instant.now(), Map.of());
        AgentConfig config = AgentConfig.defaults();
        AgentTask task = new AgentTask("test goal", Map.of(), null, config);
        ctx = new AgentContext(execCtx, task, 0);

        MemoryId wmId = MemoryId.of(ctx.getAgentId(), MemoryType.WORKING, "100");
        wm = new WorkingMemory(wmId, PlanNode.goal("root", "test goal"));
    }

    // —— beforeTask ——

    @Test
    @DisplayName("beforeTask creates WorkingMemory when enabled")
    void beforeTaskEnabled() {
        when(memoryRuntime.getOrCreateWorkingMemory(any(MemoryContext.class))).thenReturn(wm);

        WorkingMemory result = bridge.beforeTask(ctx);

        assertThat(result).isNotNull();
        assertThat(result.getId().id()).isEqualTo(ctx.getAgentId());
        verify(memoryRuntime).getOrCreateWorkingMemory(any(MemoryContext.class));
    }

    @Test
    @DisplayName("beforeTask returns null when workingMemoryEnabled is false")
    void beforeTaskDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(true, false, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        WorkingMemory result = disabledBridge.beforeTask(ctx);

        assertThat(result).isNull();
        verify(memoryRuntime, never()).getOrCreateWorkingMemory(any());
    }

    // —— recordStep ——

    @Test
    @DisplayName("recordStep appends to Episodic timeline")
    void recordStepEnabled() {
        ctx.incrementStep();
        Step step = Step.of("s1", "search knowledge base");
        StepResult result = StepResult.ok("found 3 documents", 100);
        MemoryCell expectedCell = MemoryCell.of(MemoryCellId.of("cell-1"), MemoryType.EPISODIC, "test");
        when(memoryRuntime.append(any(MemoryId.class), anyString(), any(MemoryContext.class)))
                .thenReturn(expectedCell);

        MemoryCell cell = bridge.recordStep(wm, step, result, ctx);

        assertThat(cell).isNotNull();
        verify(memoryRuntime).append(
                argThat(id -> id.type() == MemoryType.EPISODIC),
                contains("[step:1] search knowledge base → found 3 documents"),
                any(MemoryContext.class));
    }

    @Test
    @DisplayName("recordStep returns null when episodicEnabled is false")
    void recordStepDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(false, true, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        MemoryCell cell = disabledBridge.recordStep(wm, Step.of("s1", "test"),
                StepResult.ok("output", 0), ctx);

        assertThat(cell).isNull();
        verify(memoryRuntime, never()).append(any(), anyString(), any());
    }

    @Test
    @DisplayName("recordStep returns null when wm is null")
    void recordStepNullWm() {
        MemoryCell cell = bridge.recordStep(null, Step.of("s1", "test"),
                StepResult.ok("output", 0), ctx);

        assertThat(cell).isNull();
    }

    @Test
    @DisplayName("recordStep handles null step output gracefully")
    void recordStepNullOutput() {
        Step step = Step.of("s1", "query db");
        StepResult result = StepResult.fail("timeout", 5000);
        when(memoryRuntime.append(any(), anyString(), any()))
                .thenReturn(MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, ""));

        MemoryCell cell = bridge.recordStep(wm, step, result, ctx);

        assertThat(cell).isNotNull();
        verify(memoryRuntime).append(any(), contains("(no output)"), any());
    }

    // —— recordObservation ——

    @Test
    @DisplayName("recordObservation appends observation to Episodic")
    void recordObservationEnabled() {
        ctx.incrementStep();
        Observation obs = Observation.success("step completed successfully");
        when(memoryRuntime.append(any(), anyString(), any()))
                .thenReturn(MemoryCell.of(MemoryCellId.of("c2"), MemoryType.EPISODIC, ""));

        MemoryCell cell = bridge.recordObservation(wm, obs, ctx);

        assertThat(cell).isNotNull();
        verify(memoryRuntime).append(
                argThat(id -> id.type() == MemoryType.EPISODIC),
                contains("[observe:1] SUCCESS: step completed successfully"),
                any());
    }

    @Test
    @DisplayName("recordObservation returns null when episodic disabled")
    void recordObservationDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(false, true, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        MemoryCell cell = disabledBridge.recordObservation(wm, Observation.failure("err"), ctx);

        assertThat(cell).isNull();
    }

    @Test
    @DisplayName("recordObservation returns null when wm is null")
    void recordObservationNullWm() {
        MemoryCell cell = bridge.recordObservation(null, Observation.success("ok"), ctx);

        assertThat(cell).isNull();
    }

    // —— recordDecision ——

    @Test
    @DisplayName("recordDecision appends decision to Episodic")
    void recordDecisionEnabled() {
        ctx.incrementStep();
        when(memoryRuntime.append(any(), anyString(), any()))
                .thenReturn(MemoryCell.of(MemoryCellId.of("c3"), MemoryType.EPISODIC, ""));

        MemoryCell cell = bridge.recordDecision(wm, Decision.CONTINUE, ctx);

        assertThat(cell).isNotNull();
        verify(memoryRuntime).append(
                argThat(id -> id.type() == MemoryType.EPISODIC),
                contains("[reflect:1] CONTINUE"),
                any());
    }

    @Test
    @DisplayName("recordDecision returns null when episodic disabled")
    void recordDecisionDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(false, true, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        MemoryCell cell = disabledBridge.recordDecision(wm, Decision.COMPLETE, ctx);

        assertThat(cell).isNull();
    }

    // —— createResumePoint ——

    @Test
    @DisplayName("createResumePoint delegates to MemoryRuntime")
    void createResumePointEnabled() {
        ResumePoint expected = ResumePoint.of("step_3", "plan-node-1", Map.of(), List.of());
        when(memoryRuntime.createResumePoint(eq(wm.getId()), eq("step_3"))).thenReturn(expected);

        ResumePoint point = bridge.createResumePoint(wm, "step_3");

        assertThat(point).isNotNull();
        assertThat(point.label()).isEqualTo("step_3");
        verify(memoryRuntime).createResumePoint(wm.getId(), "step_3");
    }

    @Test
    @DisplayName("createResumePoint returns null when workingMemory disabled")
    void createResumePointDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(true, false, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        ResumePoint point = disabledBridge.createResumePoint(wm, "label");

        assertThat(point).isNull();
        verify(memoryRuntime, never()).createResumePoint(any(), anyString());
    }

    @Test
    @DisplayName("createResumePoint returns null when wm is null")
    void createResumePointNullWm() {
        ResumePoint point = bridge.createResumePoint(null, "label");

        assertThat(point).isNull();
    }

    // —— afterTask ——

    @Test
    @DisplayName("afterTask writes [complete] to Episodic")
    void afterTaskEnabled() {
        when(memoryRuntime.append(any(), anyString(), any()))
                .thenReturn(MemoryCell.of(MemoryCellId.of("c4"), MemoryType.EPISODIC, ""));

        bridge.afterTask(wm, ctx, "task completed successfully");

        verify(memoryRuntime).append(
                argThat(id -> id.type() == MemoryType.EPISODIC),
                contains("[complete] task completed successfully"),
                any());
    }

    @Test
    @DisplayName("afterTask does nothing when episodic disabled")
    void afterTaskDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(false, true, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        disabledBridge.afterTask(wm, ctx, "done");

        verify(memoryRuntime, never()).append(any(), anyString(), any());
    }

    @Test
    @DisplayName("afterTask does nothing when wm is null")
    void afterTaskNullWm() {
        bridge.afterTask(null, ctx, "done");

        verify(memoryRuntime, never()).append(any(), anyString(), any());
    }

    // —— onTaskFailure ——

    @Test
    @DisplayName("onTaskFailure saves WorkingMemory and creates ResumePoint")
    void onTaskFailureEnabled() {
        ResumePoint errorPoint = ResumePoint.of("error_ts", "pn-1", Map.of(), List.of());
        when(memoryRuntime.createResumePoint(eq(wm.getId()), anyString())).thenReturn(errorPoint);

        Optional<ResumePoint> result = bridge.onTaskFailure(wm, ctx,
                new RuntimeException("execution failed"));

        assertThat(result).isPresent();
        assertThat(result.get().label()).startsWith("error_");
        verify(memoryRuntime).saveWorkingMemory(wm);
        verify(memoryRuntime).createResumePoint(eq(wm.getId()), anyString());
    }

    @Test
    @DisplayName("onTaskFailure returns empty when workingMemory disabled")
    void onTaskFailureDisabled() {
        AgentMemoryConfig disabledConfig = new AgentMemoryConfig(true, false, false, 200, 5, false, 3);
        AgentMemoryBridge disabledBridge = new AgentMemoryBridge(memoryRuntime, disabledConfig);

        Optional<ResumePoint> result = disabledBridge.onTaskFailure(wm, ctx,
                new RuntimeException("err"));

        assertThat(result).isEmpty();
        verify(memoryRuntime, never()).saveWorkingMemory(any());
    }

    @Test
    @DisplayName("onTaskFailure returns empty when wm is null")
    void onTaskFailureNullWm() {
        Optional<ResumePoint> result = bridge.onTaskFailure(null, ctx, new RuntimeException("err"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("onTaskFailure returns empty when createResumePoint throws")
    void onTaskFailureCreateError() {
        doThrow(new RuntimeException("store error")).when(memoryRuntime).createResumePoint(any(), anyString());

        Optional<ResumePoint> result = bridge.onTaskFailure(wm, ctx, new RuntimeException("exec error"));

        assertThat(result).isEmpty();
        verify(memoryRuntime).saveWorkingMemory(wm);
    }

    // —— recall ——

    @Test
    @DisplayName("recall searches Episodic+Semantic when semanticSearch enabled")
    void recallWithSemantic() {
        AgentMemoryConfig config = new AgentMemoryConfig(true, true, true, 200, 5, false, 3);
        AgentMemoryBridge semanticBridge = new AgentMemoryBridge(memoryRuntime, config);
        QueryResult expectedResult = QueryResult.of(List.of(), 0, Duration.ZERO);
        when(memoryRuntime.retrieve(any(MemoryQuery.class), any(MemoryContext.class)))
                .thenReturn(expectedResult);

        QueryResult result = semanticBridge.recall("AI chips", ctx, 5);

        assertThat(result).isNotNull();
        verify(memoryRuntime).retrieve(
                argThat(q -> q.topK() == 5 && q.textQuery().equals("AI chips")),
                any(MemoryContext.class));
        // Filter should include both EPISODIC and SEMANTIC
        verify(memoryRuntime).retrieve(
                argThat(q -> q.searchTypes().contains(MemoryType.EPISODIC)
                        && q.searchTypes().contains(MemoryType.SEMANTIC)),
                any());
    }

    @Test
    @DisplayName("recall searches only Episodic when semanticSearch disabled")
    void recallEpisodicOnly() {
        QueryResult expectedResult = QueryResult.of(List.of(), 0, Duration.ZERO);
        when(memoryRuntime.retrieve(any(), any())).thenReturn(expectedResult);

        QueryResult result = bridge.recall("test query", ctx, 3);

        assertThat(result).isNotNull();
        verify(memoryRuntime).retrieve(
                argThat(q -> q.searchTypes().size() == 1
                        && q.searchTypes().contains(MemoryType.EPISODIC)
                        && !q.searchTypes().contains(MemoryType.SEMANTIC)),
                any());
    }

    // —— readRecentTimeline ——

    @Test
    @DisplayName("readRecentTimeline reads Episodic timeline")
    void readRecentTimeline() {
        MemoryCell cell1 = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "event 1");
        MemoryCell cell2 = MemoryCell.of(MemoryCellId.of("c2"), MemoryType.EPISODIC, "event 2");
        when(memoryRuntime.readTimeline(any(MemoryId.class), eq(5)))
                .thenReturn(List.of(cell1, cell2));

        List<MemoryCell> timeline = bridge.readRecentTimeline(ctx, 5);

        assertThat(timeline).hasSize(2);
        verify(memoryRuntime).readTimeline(
                argThat(id -> id.type() == MemoryType.EPISODIC),
                eq(5));
    }

    // —— WorkingMemory variable operations ——

    @Test
    @DisplayName("putVariable and getVariable round-trip")
    void putGetVariable() {
        bridge.putVariable(wm, "key1", "value1");
        bridge.putVariable(wm, "count", 42);

        assertThat(bridge.<String>getVariable(wm, "key1", String.class)).hasValue("value1");
        assertThat(bridge.<Integer>getVariable(wm, "count", Integer.class)).hasValue(42);
    }

    @Test
    @DisplayName("putVariable with null wm is no-op")
    void putVariableNullWm() {
        bridge.putVariable(null, "key", "val");
        // no exception expected
    }

    @Test
    @DisplayName("getVariable with null wm returns empty")
    void getVariableNullWm() {
        Optional<String> result = bridge.getVariable(null, "key", String.class);

        assertThat(result).isEmpty();
    }

    // —— saveWorkingMemory ——

    @Test
    @DisplayName("saveWorkingMemory delegates to MemoryRuntime")
    void saveWorkingMemory() {
        bridge.saveWorkingMemory(wm);

        verify(memoryRuntime).saveWorkingMemory(wm);
    }

    @Test
    @DisplayName("saveWorkingMemory with null wm is no-op")
    void saveWorkingMemoryNullWm() {
        bridge.saveWorkingMemory(null);

        verify(memoryRuntime, never()).saveWorkingMemory(any());
    }

    // —— snapshot ——

    @Test
    @DisplayName("snapshot returns MemorySnapshot from MemoryRuntime")
    void snapshotEnabled() {
        MemorySnapshot expected = new MemorySnapshot(wm.getId(), MemoryType.WORKING,
                LifecycleState.ACTIVE, 10, 1024, new MemorySpan(Instant.now(), null, 0), Map.of());
        when(memoryRuntime.snapshot(wm.getId())).thenReturn(expected);

        MemorySnapshot snap = bridge.snapshot(wm);

        assertThat(snap).isNotNull();
        assertThat(snap.id()).isEqualTo(wm.getId());
        verify(memoryRuntime).snapshot(wm.getId());
    }

    @Test
    @DisplayName("snapshot with null wm returns null")
    void snapshotNullWm() {
        MemorySnapshot snap = bridge.snapshot(null);

        assertThat(snap).isNull();
    }

    // —— AgentMemoryConfig ——

    @Test
    @DisplayName("AgentMemoryConfig defaults have expected values")
    void configDefaults() {
        AgentMemoryConfig config = AgentMemoryConfig.defaults();

        assertThat(config.episodicEnabled()).isTrue();
        assertThat(config.workingMemoryEnabled()).isTrue();
        assertThat(config.semanticSearchEnabled()).isFalse();
        assertThat(config.episodicTimelineLimit()).isEqualTo(200);
        assertThat(config.defaultRecallTopK()).isEqualTo(5);
        assertThat(config.autoConsolidate()).isFalse();
        assertThat(config.resumePointInterval()).isEqualTo(3);
    }

    // —— memoryRuntime accessor ——

    @Test
    @DisplayName("memoryRuntime() returns the underlying MemoryRuntime")
    void memoryRuntimeAccessor() {
        assertThat(bridge.memoryRuntime()).isSameAs(memoryRuntime);
    }
}
