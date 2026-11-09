package com.yin.ai.platform.agent.event;

import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventProtocolTest {

    @Test
    void intermediateArtifactRequiresExplicitPublicationDecision() {
        String output = "# Architecture draft\n\n"
                + "First section explains the runtime and event transport. ".repeat(5)
                + "\n\n"
                + "Second section explains replay, ownership, and delivery. ".repeat(5);

        AgentEvent hidden = AgentEvent.stepCompleted("task-1", "step-2", output);
        AgentEvent published = AgentEvent.stepCompleted("task-1", "step-2", output, true);

        assertThat(hidden.payload()).doesNotContainKey("artifactContent");
        assertThat(published.type()).isEqualTo(TaskEventType.STEP_COMPLETED);
        assertThat(published.payload().get("artifactContent")).isEqualTo(output.trim());
        assertThat(published.payload()).containsEntry("artifactDecision", "llm");
        assertThat(published.payload().get("output")).isNotEqualTo(output.trim());
        assertThat(published.payload().get("outputChars")).isEqualTo(output.trim().length());
    }

    @Test
    void shortStepOutputRemainsSummaryOnly() {
        AgentEvent event = AgentEvent.stepCompleted("task-1", "step-1", "Short result");

        assertThat(event.payload()).doesNotContainKey("artifactContent");
        assertThat(event.payload().get("output")).isEqualTo("Short result");
    }

    @Test
    void planLifecycleUsesFrontendContractShapes() {
        TaskPlan plan = new TaskPlan("write an article", List.of(
                TaskStep.pending("research", "Research current facts", "web_search", List.of(), "avoid stale facts"),
                TaskStep.pending("draft", "Draft and edit article", "llm", List.of("research"))));

        AgentEvent created = AgentEvent.planCreated("task-1", plan);
        AgentEvent updated = AgentEvent.planUpdated("task-1", plan);
        AgentEvent restored = AgentEvent.planUpdated("task-1", plan, true);
        AgentEvent summary = AgentEvent.planSummary("task-1", plan, false);

        assertThat(created.type()).isEqualTo(TaskEventType.PLAN_CREATED);
        assertThat(created.payload()).containsEntry("totalSteps", 2).containsKey("steps");
        assertThat(updated.type()).isEqualTo(TaskEventType.PLAN_UPDATED);
        assertThat(updated.payload()).containsEntry("totalSteps", 2).containsKey("steps");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> steps =
                (List<java.util.Map<String, Object>>) updated.payload().get("steps");
        assertThat(steps.get(0)).containsEntry("reason", "avoid stale facts");
        assertThat(restored.payload()).containsEntry("restored", true);
        assertThat(summary.type()).isEqualTo(TaskEventType.PLAN_SUMMARY);
        assertThat(summary.payload().get("summary").toString()).contains("Research current facts");
    }

    @Test
    void taskLifecycleHasExplicitPlanningAndRunningEvents() {
        assertThat(AgentEvent.taskPlanning("task-1").type()).isEqualTo(TaskEventType.TASK_PLANNING);
        assertThat(AgentEvent.taskStarted("task-1").type()).isEqualTo(TaskEventType.TASK_RUNNING);
    }
}
