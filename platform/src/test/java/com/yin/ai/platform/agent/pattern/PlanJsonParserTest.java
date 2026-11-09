package com.yin.ai.platform.agent.pattern;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonParserTest {

    private static final Predicate<String> AVAILABLE =
            executor -> "llm".equals(executor) || "web_search".equals(executor);

    @Test
    void parsesCleanJsonArray() {
        String json = """
                [{"id": "analyze", "description": "分析任务需求", "executor": "llm", "dependsOn": []},
                 {"id": "execute", "description": "执行核心任务", "executor": "llm", "dependsOn": ["analyze"]}]
                """;

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.goal()).isEqualTo("goal");
        assertThat(plan.totalSteps()).isEqualTo(2);
        assertThat(plan.steps().get(0).id()).isEqualTo("analyze");
        assertThat(plan.steps().get(1).dependsOn()).containsExactly("analyze");
    }

    @Test
    void parsesFencedJson() {
        String json = "```json\n"
                + "[{\"id\": \"a\", \"description\": \"第一步\", \"executor\": \"llm\", \"dependsOn\": []}]\n"
                + "```";

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.totalSteps()).isEqualTo(1);
        assertThat(plan.steps().get(0).id()).isEqualTo("a");
    }

    @Test
    void parsesJsonWithPreambleAndTrailingProse() {
        String json = "好的，这是计划：\n"
                + "[{\"id\": \"a\", \"description\": \"第一步\", \"executor\": \"llm\", \"dependsOn\": []}]\n"
                + "希望对你有帮助！";

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.totalSteps()).isEqualTo(1);
    }

    @Test
    void parsesObjectWrappedArray() {
        String json = "{\"steps\": "
                + "[{\"id\": \"a\", \"description\": \"第一步\", \"executor\": \"llm\", \"dependsOn\": []}]}";

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.totalSteps()).isEqualTo(1);
    }

    @Test
    void skipsStepsWithBlankIdOrDescription() {
        String json = """
                [{"id": "", "description": "空id", "executor": "llm", "dependsOn": []},
                 {"id": "b", "description": "   ", "executor": "llm", "dependsOn": []},
                 {"id": "c", "description": "有效步骤", "executor": "llm", "dependsOn": []}]
                """;

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.totalSteps()).isEqualTo(1);
        assertThat(plan.steps().get(0).id()).isEqualTo("c");
    }

    @Test
    void deduplicatesIds() {
        String json = """
                [{"id": "step", "description": "一", "executor": "llm", "dependsOn": []},
                 {"id": "step", "description": "二", "executor": "llm", "dependsOn": []}]
                """;

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan.steps().get(0).id()).isEqualTo("step");
        assertThat(plan.steps().get(1).id()).isEqualTo("step-2");
    }

    @Test
    void degradesUnavailableExecutorToLlm() {
        String json = "[{\"id\": \"a\", \"description\": \"读全文\", \"executor\": \"view_artifact\", \"dependsOn\": []}]";

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.steps().get(0).executor()).isEqualTo("llm");
    }

    @Test
    void trimsDanglingAndSelfDependencies() {
        String json = """
                [{"id": "a", "description": "一", "executor": "llm", "dependsOn": ["missing", "a"]},
                 {"id": "b", "description": "二", "executor": "llm", "dependsOn": ["a", "ghost"]}]
                """;

        TaskPlan plan = PlanJsonParser.parse(json, "goal", AVAILABLE);

        assertThat(plan.steps().get(0).dependsOn()).isEmpty();
        assertThat(plan.steps().get(1).dependsOn()).containsExactly("a");
    }

    @Test
    void capsStepsAtEight() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\": \"s").append(i).append("\", \"description\": \"步骤").append(i)
                    .append("\", \"executor\": \"llm\", \"dependsOn\": []}");
        }
        sb.append("]");

        TaskPlan plan = PlanJsonParser.parse(sb.toString(), "goal", AVAILABLE);

        assertThat(plan).isNotNull();
        assertThat(plan.totalSteps()).isEqualTo(8);
    }

    @Test
    void returnsNullForGarbage() {
        assertThat(PlanJsonParser.parse("这根本不是 JSON", "goal", AVAILABLE)).isNull();
        assertThat(PlanJsonParser.parse("", "goal", AVAILABLE)).isNull();
        assertThat(PlanJsonParser.parse(null, "goal", AVAILABLE)).isNull();
        assertThat(PlanJsonParser.parse("{\"foo\": \"bar\"}", "goal", AVAILABLE)).isNull();
    }

    @Test
    void extractJsonHandlesArrayOfObjectsWithoutTruncation() {
        String raw = "preamble [{\"id\": \"a\"}, {\"id\": \"b\"}] trailing";

        String json = PlanJsonParser.extractJson(raw);

        assertThat(json).isEqualTo("[{\"id\": \"a\"}, {\"id\": \"b\"}]");
    }

    // ---- parseNewSteps（REPLAN 追加步骤）----

    @Test
    void parsesNewStepsFromReplanObject() {
        String json = """
                {"decision":"REPLAN","reason":"需要补充调研",
                 "newSteps":[
                   {"id":"extra-research","description":"补充竞品调研","executor":"web_search","dependsOn":["search"]}
                 ]}
                """;

        List<TaskStep> steps = PlanJsonParser.parseNewSteps(json, AVAILABLE);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).id()).isEqualTo("extra-research");
        assertThat(steps.get(0).executor()).isEqualTo("web_search");
        // trimDeps=false：允许引用既有计划中的步骤 id
        assertThat(steps.get(0).dependsOn()).containsExactly("search");
    }

    @Test
    void parsesNewStepsFromBareArray() {
        String json = "[{\"id\": \"s1\", \"description\": \"追加步骤\", \"executor\": \"llm\", \"dependsOn\": []}]";

        List<TaskStep> steps = PlanJsonParser.parseNewSteps(json, AVAILABLE);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).id()).isEqualTo("s1");
    }

    @Test
    void capsNewStepsAtFour() {
        StringBuilder sb = new StringBuilder("{\"newSteps\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\": \"n").append(i).append("\", \"description\": \"步").append(i)
                    .append("\", \"executor\": \"llm\", \"dependsOn\": []}");
        }
        sb.append("]}");

        List<TaskStep> steps = PlanJsonParser.parseNewSteps(sb.toString(), AVAILABLE);

        assertThat(steps).hasSize(4);
    }

    @Test
    void degradesUnavailableNewStepExecutorToLlm() {
        String json = "{\"newSteps\":[{\"id\": \"v\", \"description\": \"回读产物\", \"executor\": \"view_artifact\", \"dependsOn\": []}]}";

        List<TaskStep> steps = PlanJsonParser.parseNewSteps(json, AVAILABLE);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).executor()).isEqualTo("llm");
    }

    @Test
    void returnsEmptyForGarbage() {
        assertThat(PlanJsonParser.parseNewSteps("这根本不是 JSON", AVAILABLE)).isEmpty();
        assertThat(PlanJsonParser.parseNewSteps("{\"foo\": 1}", AVAILABLE)).isEmpty();
        assertThat(PlanJsonParser.parseNewSteps(null, AVAILABLE)).isEmpty();
    }
}
