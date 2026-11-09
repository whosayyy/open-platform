package com.yin.ai.platform.core.memory.procedural;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProcedureStep 模型")
class ProcedureStepTest {

    @Test
    @DisplayName("think 工厂方法")
    void shouldCreateThinkStep() {
        ProcedureStep step = ProcedureStep.think(1, "analyze requirements");

        assertThat(step.seq()).isEqualTo(1);
        assertThat(step.type()).isEqualTo(ProcedureStepType.THINK);
        assertThat(step.toolName()).isEmpty();
        assertThat(step.params()).containsEntry("description", "analyze requirements");
    }

    @Test
    @DisplayName("toolCall 工厂方法")
    void shouldCreateToolCallStep() {
        Map<String, Object> params = Map.of("query", "weather", "location", "Beijing");
        ProcedureStep step = ProcedureStep.toolCall(2, "search", params);

        assertThat(step.seq()).isEqualTo(2);
        assertThat(step.type()).isEqualTo(ProcedureStepType.TOOL_CALL);
        assertThat(step.toolName()).isEqualTo("search");
        assertThat(step.params()).containsEntry("query", "weather");
    }

    @Test
    @DisplayName("observe 工厂方法")
    void shouldCreateObserveStep() {
        ProcedureStep step = ProcedureStep.observe(3, "search returned 5 results");

        assertThat(step.type()).isEqualTo(ProcedureStepType.OBSERVE);
        assertThat(step.params()).containsEntry("observation", "search returned 5 results");
    }

    @Test
    @DisplayName("act 工厂方法")
    void shouldCreateActStep() {
        ProcedureStep step = ProcedureStep.act(4, "generate report");

        assertThat(step.type()).isEqualTo(ProcedureStepType.ACT);
        assertThat(step.params()).containsEntry("description", "generate report");
    }

    @Test
    @DisplayName("successRate clamp 到 [0.0, 1.0]")
    void shouldClampSuccessRate() {
        ProcedureStep over = ProcedureStep.think(1, "x").withSuccessRate(2.0);
        assertThat(over.successRate()).isEqualTo(1.0);

        ProcedureStep under = ProcedureStep.think(1, "x").withSuccessRate(-0.5);
        assertThat(under.successRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("withSuccessRate 返回新实例")
    void shouldUpdateSuccessRate() {
        ProcedureStep original = ProcedureStep.think(1, "task");
        ProcedureStep updated = original.withSuccessRate(0.75);

        assertThat(updated.successRate()).isEqualTo(0.75);
        assertThat(original.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("null 字段使用默认值")
    void shouldHandleNullFields() {
        ProcedureStep step = new ProcedureStep(1, ProcedureStepType.THINK,
                null, null, null, null, 0.5, null);

        assertThat(step.toolName()).isEmpty();
        assertThat(step.params()).isEmpty();
        assertThat(step.preconditions()).isEmpty();
        assertThat(step.postconditions()).isEmpty();
        assertThat(step.metadata()).isEmpty();
    }
}
