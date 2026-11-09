package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LLMPlanner")
class LLMPlannerTest {

    private ExecutionContext execCtx;
    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        ctx = new AgentContext(execCtx, AgentTask.of("分析AI芯片市场"), 0);
    }

    @Test
    @DisplayName("LLM returns valid JSON → parsed into Plan with steps")
    void validJsonResponse() {
        String json = """
                {
                  "steps": [
                    {"id": "s1", "description": "搜索市场数据", "tool": "searchNews",
                     "toolParams": {"query": "AI芯片"}, "dependsOn": []},
                    {"id": "s2", "description": "分析数据", "tool": null,
                     "toolParams": {}, "dependsOn": ["s1"]}
                  ]
                }""";

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.hasSteps()).isTrue();
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).id()).isEqualTo("s1");
        assertThat(plan.steps().get(0).tool()).isEqualTo("searchNews");
        assertThat(plan.steps().get(0).toolParams()).containsEntry("query", "AI芯片");
        assertThat(plan.steps().get(1).dependsOn()).containsExactly("s1");
    }

    @Test
    @DisplayName("LLM returns direct response → Plan.direct")
    void directResponse() {
        String json = """
                {"direct": "AI芯片市场目前由NVIDIA主导"}""";

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.isDirectResult()).isTrue();
        assertThat(plan.result()).isEqualTo("AI芯片市场目前由NVIDIA主导");
    }

    @Test
    @DisplayName("LLM returns invalid JSON → degrades to Plan.empty")
    void invalidJsonDegrades() {
        StubChatClient stub = new StubChatClient("not valid json at all");
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.hasSteps()).isFalse();
        assertThat(plan.isDirectResult()).isFalse();
    }

    @Test
    @DisplayName("LLM returns empty steps → degrades to Plan.empty")
    void emptyStepsDegrades() {
        String json = """
                {"steps": []}""";

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.hasSteps()).isFalse();
    }

    @Test
    @DisplayName("LLM returns null content → degrades to Plan.empty")
    void nullContentDegrades() {
        StubChatClient stub = new StubChatClient(null);
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.hasSteps()).isFalse();
    }

    @Test
    @DisplayName("JSON wrapped in markdown code fences → extractJson strips fence")
    void jsonInMarkdownFences() {
        String json = """
                ```json
                {
                  "steps": [
                    {"id": "s1", "description": "do it", "tool": null, "toolParams": {}, "dependsOn": []}
                  ]
                }
                ```""";

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.hasSteps()).isTrue();
        assertThat(plan.steps()).hasSize(1);
    }

    @Test
    @DisplayName("step with missing description → filtered out")
    void missingDescriptionSkipped() {
        String json = """
                {
                  "steps": [
                    {"id": "s1", "tool": null, "dependsOn": []},
                    {"id": "s2", "description": "valid step", "tool": null,
                     "toolParams": {}, "dependsOn": []}
                  ]
                }""";

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub);

        Plan plan = planner.plan(ctx);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).id()).isEqualTo("s2");
    }

    @Test
    @DisplayName("custom PlannerPromptStrategy is used for prompt generation")
    void customPromptStrategy() {
        String json = """
                {"steps": [{"id": "s1", "description": "do", "tool": null,
                 "toolParams": {}, "dependsOn": []}]}""";

        PlannerPromptStrategy customStrategy = new PlannerPromptStrategy() {
            @Override
            public String buildSystemPrompt() {
                return "CUSTOM SYSTEM";
            }

            @Override
            public String buildUserMessage(String goal,
                                            java.util.Collection<FunctionDefinition> tools) {
                return "CUSTOM USER: " + goal;
            }
        };

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub, null, customStrategy);

        Plan plan = planner.plan(ctx);

        assertThat(plan.hasSteps()).isTrue();
        assertThat(stub.lastRequest.getMessages().get(0).getContent()).isEqualTo("CUSTOM SYSTEM");
        assertThat(stub.lastRequest.getMessages().get(1).getContent()).isEqualTo(
                "CUSTOM USER: 分析AI芯片市场");
    }

    @Test
    @DisplayName("GatewayRequest has correct caller and temperature")
    void gatewayRequestStructure() {
        String json = """
                {"steps": [{"id": "s1", "description": "search", "tool": "searchNews",
                 "toolParams": {}, "dependsOn": []}]}""";

        StubChatClient stub = new StubChatClient(json);
        LLMPlanner planner = new LLMPlanner(stub);

        planner.plan(ctx);

        assertThat(stub.lastRequest.getCaller()).isEqualTo("agent.planner");
        assertThat(stub.lastRequest.getOptions().getTemperature()).isEqualTo(0.3);
        assertThat(stub.lastRequest.getOptions().isEnableFunctionCalling()).isFalse();
    }

    @Test
    @DisplayName("RAG evidence is injected into planner with citation constraints")
    void ragEvidenceIsVisibleToPlanner() {
        ctx.putVariable("agent.ragContext", "### [E1] 审核资料\nNVIDIA 市占率数据");
        StubChatClient stub = new StubChatClient("{\"direct\":\"结论 [E1]\"}");

        new LLMPlanner(stub).plan(ctx);

        assertThat(stub.lastRequest.getMessages()).hasSize(3);
        assertThat(stub.lastRequest.getMessages().get(1).getContent())
                .contains("知识库证据约束", "[E1]", "NVIDIA 市占率数据");
    }

    @Nested
    @DisplayName("extractJson")
    class ExtractJsonTest {

        @Test
        @DisplayName("plain JSON returned as-is")
        void plainJson() {
            String result = LLMPlanner.extractJson("{\"key\": \"value\"}");
            assertThat(result).isEqualTo("{\"key\": \"value\"}");
        }

        @Test
        @DisplayName("JSON with surrounding text → extract { } block")
        void jsonWithSurroundingText() {
            String result = LLMPlanner.extractJson("Sure! Here is the plan:\n{\"steps\": []}\nHope this helps.");
            assertThat(result).isEqualTo("{\"steps\": []}");
        }

        @Test
        @DisplayName("```json fence → extract content between fences")
        void codeFence() {
            String input = "```json\n{\"steps\": []}\n```";
            assertThat(LLMPlanner.extractJson(input)).isEqualTo("{\"steps\": []}");
        }

        @Test
        @DisplayName("```json fence with surrounding text")
        void codeFenceWithText() {
            String input = "Here is your plan:\n```json\n{\"direct\": \"ok\"}\n```\nDone.";
            assertThat(LLMPlanner.extractJson(input)).isEqualTo("{\"direct\": \"ok\"}");
        }
    }

    @Nested
    @DisplayName("PlanResponse DTO")
    class PlanResponseTest {

        @Test
        @DisplayName("hasSteps true when steps present")
        void hasStepsTrue() {
            var def = new PlanResponse.StepDef("s1", "desc", null, Map.of(), List.of(), "why");
            var response = new PlanResponse(null, List.of(def));
            assertThat(response.hasSteps()).isTrue();
            assertThat(response.hasDirect()).isFalse();
        }

        @Test
        @DisplayName("hasDirect true when direct present")
        void hasDirectTrue() {
            var response = new PlanResponse("answer", null);
            assertThat(response.hasDirect()).isTrue();
            assertThat(response.hasSteps()).isFalse();
        }

        @Test
        @DisplayName("isEmpty when both null")
        void isEmpty() {
            var response = new PlanResponse(null, null);
            assertThat(response.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("StepDef.toStep converts correctly including toolParams")
        void stepDefToStep() {
            var def = new PlanResponse.StepDef("s1", "search", "searchNews",
                    Map.of("q", "AI"), List.of("s0"), "why-search");
            Step step = def.toStep();
            assertThat(step.id()).isEqualTo("s1");
            assertThat(step.tool()).isEqualTo("searchNews");
            assertThat(step.toolParams()).containsEntry("q", "AI");
            assertThat(step.dependsOn()).containsExactly("s0");
            assertThat(step.reason()).isEqualTo("why-search");
        }
    }

    @Nested
    @DisplayName("PlannerPromptStrategy")
    class PromptStrategyTest {

        @Test
        @DisplayName("default strategy returns non-empty prompts")
        void defaultStrategy() {
            var strategy = PlannerPromptStrategy.defaults();
            assertThat(strategy.buildSystemPrompt()).isNotBlank();
            assertThat(strategy.buildUserMessage("test", List.of())).contains("test");
        }

        @Test
        @DisplayName("buildUserMessage includes tool names")
        void includesToolNames() {
            var strategy = PlannerPromptStrategy.defaults();
            var tools = List.of(
                    FunctionDefinition.builder().name("searchNews").description("搜索新闻").build(),
                    FunctionDefinition.builder().name("getDetail").description("获取详情").build());

            String msg = strategy.buildUserMessage("find news", tools);
            assertThat(msg).contains("searchNews");
            assertThat(msg).contains("搜索新闻");
            assertThat(msg).contains("getDetail");
            assertThat(msg).contains("find news");
        }

        @Test
        @DisplayName("null tools is safe")
        void nullToolsSafe() {
            var strategy = PlannerPromptStrategy.defaults();
            String msg = strategy.buildUserMessage("goal", null);
            assertThat(msg).contains("无");
        }
    }

    static class StubChatClient implements UnifiedChatClient {
        private final String content;
        GatewayRequest lastRequest;

        StubChatClient(String content) {
            this.content = content;
        }

        @Override
        public GatewayResponse chat(GatewayRequest request) {
            this.lastRequest = request;
            return new GatewayResponse(content);
        }

        @Override
        public reactor.core.publisher.Flux<com.yin.ai.platform.gateway.stream.StreamPacket> chatStream(
                GatewayRequest request) {
            throw new UnsupportedOperationException("stream not used in planner");
        }
    }
}
