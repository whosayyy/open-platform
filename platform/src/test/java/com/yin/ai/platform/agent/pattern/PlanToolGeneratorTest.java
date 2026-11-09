package com.yin.ai.platform.agent.pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanToolGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLAN_ARGS = """
            {"goal":"goal","steps":[
              {"id":"a","description":"step A","executor":"llm","dependsOn":[]},
              {"id":"b","description":"step B","executor":"llm","dependsOn":["a"]}
            ]}
            """;

    @Test
    void submitPlanToolDefinesPlanContractSchema() throws Exception {
        var tool = PlanToolGenerator.submitPlanTool();
        JsonNode schema = MAPPER.readTree(tool.getToolDefinition().inputSchema());
        assertThat(tool.getToolDefinition().name()).isEqualTo("submitPlan");
        assertThat(stringsOf(schema.path("required"))).containsExactlyInAnyOrder("goal", "steps");
        assertThat(stringsOf(schema.path("properties").path("steps").path("items").path("required")))
                .containsExactlyInAnyOrder("id", "description", "executor", "dependsOn");
    }

    @Test
    void extractsMatchingToolCallAndParsesPlan() {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "submitPlan", PLAN_ARGS))).build();
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));
        assertThat(PlanToolGenerator.extractToolCallArguments(response, "submitPlan"))
                .contains(PLAN_ARGS);
        assertThat(PlanToolGenerator.extractToolCallArguments(response, "other")).isEmpty();

        TaskPlan plan = PlanToolGenerator.parsePlanArguments(
                PLAN_ARGS, "goal", executor -> true).orElseThrow();
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(1).dependsOn()).containsExactly("a");
    }

    @Test
    void generateUsesGatewayStructuredFacadeAndSanitizesExecutors() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("""
                        {"goal":"goal","steps":[
                          {"id":"a","description":"search","executor":"web_search","dependsOn":[]},
                          {"id":"b","description":"analyze","executor":"forbidden","dependsOn":["a","ghost"]}
                        ]}
                        """));

        TaskPlan plan = PlanToolGenerator.generate(client, "goal", "prompt", null, 0.1,
                executor -> Set.of("llm", "web_search").contains(executor)).orElseThrow();

        assertThat(plan.steps().get(0).executor()).isEqualTo("web_search");
        assertThat(plan.steps().get(1).executor()).isEqualTo("llm");
        assertThat(plan.steps().get(1).dependsOn()).containsExactly("a");
    }

    @Test
    void generateFailsClosedForInvalidInputsOrProviderFailure() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("provider down"));
        assertThat(PlanToolGenerator.generate(client, "goal", "prompt", null, null, value -> true))
                .isEmpty();
        assertThat(PlanToolGenerator.generate(null, "goal", "prompt", null, null, value -> true))
                .isEmpty();
        assertThat(PlanToolGenerator.parsePlanArguments("not-json", "goal", value -> true)).isEmpty();
    }

    private List<String> stringsOf(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }
}
