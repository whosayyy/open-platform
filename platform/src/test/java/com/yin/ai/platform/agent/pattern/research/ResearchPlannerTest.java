package com.yin.ai.platform.agent.pattern.research;

import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.executor.StepExecutor;
import com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher;
import org.junit.jupiter.api.Test;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.structured.StructuredClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResearchPlannerTest {

    /** 空 executor 列表：dispatcher 仅 "llm" 可用，web_search/view_artifact 会被降级 */
    private final ResearchPlanner planner =
            new ResearchPlanner(mock(GatewayTextClient.class), mock(StructuredClient.class),
                    new StepExecutorDispatcher(List.<StepExecutor>of()));

    @Test
    void parsesValidLlmPlan() {
        String json = """
                [{"id": "search", "description": "广度搜索", "executor": "web_search", "dependsOn": []},
                 {"id": "synthesize", "description": "综合报告", "executor": "llm", "dependsOn": ["search"]}]
                """;

        TaskPlan plan = planner.parseResearchPlan(json, "研究目标");

        assertThat(plan).isNotNull();
        assertThat(plan.totalSteps()).isEqualTo(2);
        assertThat(plan.steps().get(0).id()).isEqualTo("search");
        assertThat(plan.steps().get(1).dependsOn()).containsExactly("search");
    }

    @Test
    void degradesUnavailableExecutorToLlm() {
        String json = "[{\"id\": \"a\", \"description\": \"读全文\", \"executor\": \"view_artifact\", \"dependsOn\": []}]";

        TaskPlan plan = planner.parseResearchPlan(json, "目标");

        assertThat(plan).isNotNull();
        assertThat(plan.steps().get(0).executor()).isEqualTo("llm");
    }

    @Test
    void returnsNullForGarbage() {
        assertThat(planner.parseResearchPlan("不是 JSON", "研究目标")).isNull();
        assertThat(planner.parseResearchPlan(null, "研究目标")).isNull();
    }
}
