package com.yin.ai.platform.core.memory.procedural;

import com.yin.ai.platform.core.memory.procedural.Procedure;
import com.yin.ai.platform.core.memory.procedural.ProcedureExecutor;
import com.yin.ai.platform.core.memory.procedural.ProcedureState;
import com.yin.ai.platform.core.memory.procedural.ProcedureStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ProcedureExecutor")
class ProcedureExecutorTest {

    private ProcedureExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ProcedureExecutor(3, 0.3);
    }

    @Test
    @DisplayName("从有效步骤列表学习 Procedure")
    void shouldLearnFromSteps() {
        List<ProcedureStep> steps = List.of(
                ProcedureStep.think(1, "analyze"),
                ProcedureStep.toolCall(2, "search", java.util.Map.of("q", "x")),
                ProcedureStep.observe(3, "got results"),
                ProcedureStep.act(4, "write summary"));

        Procedure proc = executor.recordFromSteps("write summary", steps);

        assertThat(proc.goal()).isEqualTo("write summary");
        assertThat(proc.state()).isEqualTo(ProcedureState.RECORDING);
        assertThat(proc.steps()).hasSize(4);
    }

    @Test
    @DisplayName("步骤数不足时抛出异常")
    void shouldRejectTooFewSteps() {
        List<ProcedureStep> steps = List.of(
                ProcedureStep.think(1, "one"),
                ProcedureStep.think(2, "two"));

        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> executor.recordFromSteps("goal", steps));
        assertThat(ex.getMessage()).contains("3 steps required");
    }

    @Test
    @DisplayName("goal 为空时抛出异常")
    void shouldRejectBlankGoal() {
        List<ProcedureStep> steps = List.of(
                ProcedureStep.think(1, "a"),
                ProcedureStep.think(2, "b"),
                ProcedureStep.think(3, "c"));

        assertThrows(IllegalArgumentException.class,
                () -> executor.recordFromSteps("  ", steps));
    }

    @Test
    @DisplayName("步骤为空时抛出异常")
    void shouldRejectNullSteps() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.recordFromSteps("goal", null));
    }

    @Test
    @DisplayName("错误率超阈值时拒绝学习")
    void shouldRejectHighErrorRate() {
        List<ProcedureStep> steps = List.of(
                ProcedureStep.think(1, "a").withSuccessRate(0.0), // error
                ProcedureStep.think(2, "b").withSuccessRate(0.0), // error
                ProcedureStep.think(3, "c").withSuccessRate(1.0));

        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> executor.recordFromSteps("goal", steps));
        assertThat(ex.getMessage()).contains("Error rate");
    }

    @Test
    @DisplayName("自定义参数阈值")
    void shouldRespectCustomThresholds() {
        ProcedureExecutor strict = new ProcedureExecutor(2, 0.1);
        List<ProcedureStep> steps = List.of(
                ProcedureStep.think(1, "a"),
                ProcedureStep.think(2, "b"));

        Procedure proc = strict.recordFromSteps("goal", steps);
        assertThat(proc.steps()).hasSize(2);
    }

    @Test
    @DisplayName("默认构造函数使用合理默认值")
    void shouldHaveSensibleDefaults() {
        ProcedureExecutor defaultExecutor = new ProcedureExecutor();
        assertThat(defaultExecutor.minStepsForLearning()).isEqualTo(3);
        assertThat(defaultExecutor.maxErrorRate()).isEqualTo(0.3);
    }
}
