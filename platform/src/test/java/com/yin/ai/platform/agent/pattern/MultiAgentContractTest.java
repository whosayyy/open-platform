package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.task.TaskCreateRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.yin.ai.platform.core.agent.mode.AgentMode;

class MultiAgentContractTest {

    @Test
    void shouldParseOnlyExplicitTeamModes() {
        assertThat(AgentMode.fromString("team_research")).isEqualTo(AgentMode.MULTI_AGENT);
        assertThat(AgentMode.fromString("multi_agent")).isEqualTo(AgentMode.MULTI_AGENT);
        assertThat(AgentMode.fromString("research")).isEqualTo(AgentMode.DEEP_RESEARCH);
    }

    @Test
    void shouldCarryChildLineageInTaskMetadata() {
        TaskCreateRequest child = TaskCreateRequest.child("verify sources", "7", "s1",
                AgentMode.DEEP_RESEARCH, "parent-1", "root-1", "fact_checker",
                Map.of("maxTotalTokens", "40000"));

        assertThat(child.metadata()).containsEntry("parentTaskId", "parent-1")
                .containsEntry("rootTaskId", "root-1")
                .containsEntry("agentRole", "fact_checker")
                .containsEntry("maxTotalTokens", "40000");
    }

    @Test
    void shouldExposeSummaryOnlyTeamEvents() {
        AgentEvent event = AgentEvent.teamEvent("parent-1", TaskEventType.SUBTASK_COMPLETED,
                "child-1", "source_researcher", "DEEP_RESEARCH", "COMPLETED",
                "research complete", 1234);

        assertThat(event.taskId()).isEqualTo("parent-1");
        assertThat(event.payload()).containsEntry("childTaskId", "child-1")
                .containsEntry("role", "source_researcher")
                .containsEntry("tokensUsed", 1234L)
                .doesNotContainKeys("reasoning", "chainOfThought", "rawOutput");
    }
}
