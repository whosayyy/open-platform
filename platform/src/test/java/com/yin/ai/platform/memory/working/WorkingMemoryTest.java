package com.yin.ai.platform.memory.working;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.IntermediateResult;
import com.yin.ai.platform.core.memory.working.PlanNode;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import com.yin.ai.platform.core.memory.working.WorkingMemoryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkingMemory")
class WorkingMemoryTest {

    private WorkingMemory wm;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        memoryId = MemoryId.of("wm-1", MemoryType.WORKING, "user-1");
        PlanNode root = PlanNode.goal("root", "Test task");
        PlanNode step1 = PlanNode.action("step1", "Do something", "testTool", Map.of());
        wm = new WorkingMemory(memoryId, root.addChild(step1));
    }

    @Test
    @DisplayName("initial state is ACTIVE")
    void initialState() {
        assertThat(wm.getState()).isEqualTo(WorkingMemoryState.ACTIVE);
    }

    @Test
    @DisplayName("put and get variable in global scope")
    void putAndGetVariable() {
        wm.putVariable("key", "value");
        assertThat(wm.getVariable("key", String.class)).hasValue("value");
    }

    @Test
    @DisplayName("scoped variable shadows global")
    void scopedVariable() {
        wm.putVariable("key", "global-value");
        wm.pushScope("test-scope");
        wm.putVariable("key", "scoped-value");
        assertThat(wm.getVariable("key", String.class)).hasValue("scoped-value");
        wm.popScope();
        assertThat(wm.getVariable("key", String.class)).hasValue("global-value");
    }

    @Test
    @DisplayName("resolve walks up scope chain")
    void resolveScopeChain() {
        wm.putVariable("a", "global-a");
        wm.pushScope("scope1");
        wm.putVariable("b", "scope1-b");
        assertThat(wm.resolveVariable("a")).hasValue("global-a");
        assertThat(wm.resolveVariable("b")).hasValue("scope1-b");
    }

    @Test
    @DisplayName("create resume point captures current state")
    void createResumePoint() {
        wm.putVariable("progress", 50);
        ResumePoint rp = wm.createResumePoint("before-action");
        assertThat(rp.label()).isEqualTo("before-action");
        assertThat(rp.planNodeId()).isEqualTo("root");
        assertThat(rp.variables()).containsEntry("progress", 50);
    }

    @Test
    @DisplayName("transition to FAILED prevents further transitions")
    void terminalStateTransition() {
        assertThat(wm.transitionTo(WorkingMemoryState.FAILED)).isTrue();
        assertThat(wm.transitionTo(WorkingMemoryState.ACTIVE)).isFalse();
        assertThat(wm.getState()).isEqualTo(WorkingMemoryState.FAILED);
    }

    @Test
    @DisplayName("transition to COMPLETED is terminal")
    void completedIsTerminal() {
        assertThat(wm.transitionTo(WorkingMemoryState.COMPLETED)).isTrue();
        assertThat(wm.getState().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("record and retrieve intermediate results")
    void intermediateResults() {
        IntermediateResult result = new IntermediateResult("r1", "step1", "done",
                String.class, java.time.Instant.now(), Map.of());
        wm.recordResult(result);
        assertThat(wm.getIntermediateResults()).hasSize(1);
        assertThat(wm.getIntermediateResults().get(0).resultId()).isEqualTo("r1");
    }

    @Test
    @DisplayName("find resume point by label")
    void findResumePoint() {
        wm.createResumePoint("checkpoint-1");
        wm.createResumePoint("checkpoint-2");
        Optional<ResumePoint> found = wm.findResumePoint("checkpoint-2");
        assertThat(found).isPresent();
        assertThat(found.get().label()).isEqualTo("checkpoint-2");
    }
}
