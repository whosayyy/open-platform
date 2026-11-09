package com.yin.ai.platform.core.memory.procedural;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Procedure 模型 + 状态机")
class ProcedureTest {

    @Test
    @DisplayName("record 工厂方法创建 RECORDING 状态")
    void shouldCreateRecordingProcedure() {
        Procedure proc = Procedure.record("p1", "Write unit tests");

        assertThat(proc.id()).isEqualTo("p1");
        assertThat(proc.goal()).isEqualTo("Write unit tests");
        assertThat(proc.state()).isEqualTo(ProcedureState.RECORDING);
        assertThat(proc.steps()).isEmpty();
        assertThat(proc.totalExecutions()).isEqualTo(0);
    }

    @Test
    @DisplayName("状态转换: RECORDING → VERIFIED → ACTIVE → DEPRECATED → RETIRED")
    void shouldTransitionThroughLifecycle() {
        Procedure proc = Procedure.record("p1", "goal");

        proc = proc.transitionTo(ProcedureState.VERIFIED);
        assertThat(proc.state()).isEqualTo(ProcedureState.VERIFIED);

        proc = proc.transitionTo(ProcedureState.ACTIVE);
        assertThat(proc.state()).isEqualTo(ProcedureState.ACTIVE);

        proc = proc.transitionTo(ProcedureState.DEPRECATED);
        assertThat(proc.state()).isEqualTo(ProcedureState.DEPRECATED);

        proc = proc.transitionTo(ProcedureState.RETIRED);
        assertThat(proc.state()).isEqualTo(ProcedureState.RETIRED);
    }

    @Test
    @DisplayName("非法状态转换抛出异常")
    void shouldRejectIllegalTransition() {
        Procedure proc = Procedure.record("p1", "goal")
                .transitionTo(ProcedureState.VERIFIED)
                .transitionTo(ProcedureState.ACTIVE);

        assertThrows(IllegalStateException.class,
                () -> proc.transitionTo(ProcedureState.RECORDING));
    }

    @Test
    @DisplayName("RETIRED 终态不可转换")
    void shouldBeTerminal() {
        assertThat(ProcedureState.RETIRED.isTerminal()).isTrue();
        assertThat(ProcedureState.ACTIVE.isTerminal()).isFalse();

        Procedure retired = Procedure.record("p1", "goal").transitionTo(ProcedureState.RETIRED);
        assertThrows(IllegalStateException.class,
                () -> retired.transitionTo(ProcedureState.ACTIVE));
    }

    @Test
    @DisplayName("withSteps 设置步骤列表")
    void shouldSetSteps() {
        Procedure proc = Procedure.record("p1", "goal");
        List<ProcedureStep> steps = List.of(
                ProcedureStep.think(1, "analyze"),
                ProcedureStep.toolCall(2, "search", java.util.Map.of("q", "test")));

        Procedure withSteps = proc.withSteps(steps);
        assertThat(withSteps.steps()).hasSize(2);
        assertThat(withSteps.stepCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("addStep 追加步骤")
    void shouldAddStep() {
        Procedure proc = Procedure.record("p1", "goal");
        proc = proc.addStep(ProcedureStep.think(1, "first thought"));
        proc = proc.addStep(ProcedureStep.act(2, "do something"));

        assertThat(proc.stepCount()).isEqualTo(2);
        assertThat(proc.steps().get(0).seq()).isEqualTo(1);
        assertThat(proc.steps().get(1).seq()).isEqualTo(2);
    }

    @Test
    @DisplayName("recordExecution 更新执行统计")
    void shouldRecordExecution() {
        Procedure proc = Procedure.record("p1", "goal");
        proc = proc.recordExecution(100, true);
        proc = proc.recordExecution(200, true);

        assertThat(proc.totalExecutions()).isEqualTo(2);
        assertThat(proc.avgDurationMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("withTag 添加标签")
    void shouldAddTag() {
        Procedure proc = Procedure.record("p1", "goal");
        proc = proc.withTag("unit-test").withTag("java");

        assertThat(proc.tags()).containsExactly("unit-test", "java");
    }

    @Test
    @DisplayName("ProcedureState.canTransitionTo 规则正确")
    void shouldValidateTransitionRules() {
        assertThat(ProcedureState.RECORDING.canTransitionTo(ProcedureState.VERIFIED)).isTrue();
        assertThat(ProcedureState.RECORDING.canTransitionTo(ProcedureState.RETIRED)).isTrue();
        assertThat(ProcedureState.RECORDING.canTransitionTo(ProcedureState.ACTIVE)).isFalse();

        assertThat(ProcedureState.ACTIVE.canTransitionTo(ProcedureState.DEPRECATED)).isTrue();
        assertThat(ProcedureState.DEPRECATED.canTransitionTo(ProcedureState.ACTIVE)).isTrue();
    }
}
