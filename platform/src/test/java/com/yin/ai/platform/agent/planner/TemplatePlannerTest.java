package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TemplatePlanner")
class TemplatePlannerTest {

    private ExecutionContext execCtx;

    @BeforeEach
    void setUp() {
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    private AgentContext ctx() {
        AgentTask task = AgentTask.of("test goal");
        return new AgentContext(execCtx, task, 0);
    }

    @Test
    @DisplayName("returns Plan.ofSteps when steps are provided")
    void returnsStepsPlan() {
        List<Step> steps = List.of(
                Step.of("s1", "first step"),
                Step.of("s2", "second step"));
        TemplatePlanner planner = new TemplatePlanner(steps);

        Plan plan = planner.plan(ctx());

        assertThat(plan.hasSteps()).isTrue();
        assertThat(plan.isDirectResult()).isFalse();
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).id()).isEqualTo("s1");
        assertThat(plan.steps().get(1).id()).isEqualTo("s2");
    }

    @Test
    @DisplayName("returns Plan.direct when directResult is set")
    void returnsDirectPlan() {
        TemplatePlanner planner = new TemplatePlanner(List.of(), "直接答案");

        Plan plan = planner.plan(ctx());

        assertThat(plan.isDirectResult()).isTrue();
        assertThat(plan.result()).isEqualTo("直接答案");
        assertThat(plan.hasSteps()).isFalse();
    }

    @Test
    @DisplayName("returns Plan.empty when no steps and no directResult")
    void returnsEmptyPlan() {
        TemplatePlanner planner = new TemplatePlanner(Collections.emptyList());

        Plan plan = planner.plan(ctx());

        assertThat(plan.hasSteps()).isFalse();
        assertThat(plan.isDirectResult()).isFalse();
    }

    @Test
    @DisplayName("directResult takes precedence over steps")
    void directResultWinsOverSteps() {
        List<Step> steps = List.of(Step.of("s1", "unused"));
        TemplatePlanner planner = new TemplatePlanner(steps, "direct wins");

        Plan plan = planner.plan(ctx());

        assertThat(plan.isDirectResult()).isTrue();
        assertThat(plan.result()).isEqualTo("direct wins");
    }

    @Test
    @DisplayName("null steps list is treated as empty")
    void nullStepsSafe() {
        TemplatePlanner planner = new TemplatePlanner(null);

        Plan plan = planner.plan(ctx());

        assertThat(plan.hasSteps()).isFalse();
    }

    @Test
    @DisplayName("PlanningMode enum values")
    void planningModes() {
        assertThat(PlanningMode.TEMPLATE).isNotNull();
        assertThat(PlanningMode.LLM).isNotNull();
        assertThat(PlanningMode.HYBRID).isNotNull();
    }

    @Test
    @DisplayName("new AgentConfig defaults to TEMPLATE planningMode")
    void agentConfigDefaultsToTemplate() {
        AgentConfig config = AgentConfig.defaults();
        assertThat(config.planningMode()).isEqualTo(PlanningMode.TEMPLATE);
    }

    @Test
    @DisplayName("AgentConfig.of defaults to TEMPLATE")
    void agentConfigOfDefaultsToTemplate() {
        AgentConfig config = AgentConfig.of(5, java.time.Duration.ofMinutes(1));
        assertThat(config.planningMode()).isEqualTo(PlanningMode.TEMPLATE);
    }
}
