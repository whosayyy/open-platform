package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ModelStepReflectorTest {

    @Test
    void parsesAValidatedReplanUsingOnlyAvailableExecutors() {
        RecordingModel model = new RecordingModel(response("""
                {"decision":"REPLAN","reason":"Need another source","newSteps":[
                  {"id":"verify","description":"Verify the source","executor":"search",
                   "arguments":{"query":"claim"},"dependsOn":["collect"]},
                  {"id":"verify-case","description":"Verify executor casing","executor":"SEARCH",
                   "dependsOn":["collect"]},
                  {"id":"blocked","description":"Use unavailable tool","executor":"missing"}
                ]}
                """));
        DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(new NoOpTool("search"));
        PatternContext context = context(model, tools);
        PlanStep completed = PlanStep.pending("collect", "Collect evidence", "llm",
                Map.of(), List.of()).completed("usable evidence");
        AgentPlan plan = new AgentPlan("research goal", List.of(completed));

        StepReflection reflection = ModelStepReflector.standard()
                .reflect(completed, plan, context);

        assertThat(reflection.decision()).isEqualTo(StepReflection.Decision.REPLAN);
        assertThat(reflection.newSteps()).hasSize(2);
        assertThat(reflection.newSteps().getFirst()).satisfies(step -> {
            assertThat(step.id()).isEqualTo("verify");
            assertThat(step.executor()).isEqualTo("search");
            assertThat(step.dependsOn()).containsExactly("collect");
            assertThat(step.arguments()).containsEntry("query", "claim");
        });
        assertThat(reflection.newSteps().get(1).executor()).isEqualTo("search");
        assertThat(model.request.metadata())
                .containsEntry("operation", "agent.plan.reflect")
                .containsEntry("stepId", "collect");
    }

    @Test
    void retriesEmptyOutputWithoutCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse execute(ModelRequest request, ExecutionContext context) {
                calls.incrementAndGet();
                return response("unexpected");
            }

            @Override
            public ModelCall submit(ModelRequest request, ExecutionContext context,
                                    ModelEventListener listener) {
                throw new UnsupportedOperationException();
            }
        };
        PatternContext context = context(model, new DefaultToolRegistry());
        PlanStep completed = PlanStep.pending("collect", "Collect evidence", "llm",
                Map.of(), List.of()).completed(" ");

        StepReflection reflection = ModelStepReflector.standard().reflect(completed,
                new AgentPlan("goal", List.of(completed)), context);

        assertThat(reflection.decision()).isEqualTo(StepReflection.Decision.RETRY);
        assertThat(calls).hasValue(0);
    }

    @Test
    void invalidModelOutputFailsOpenToContinue() {
        PatternContext context = context(new RecordingModel(response("not-json")),
                new DefaultToolRegistry());
        PlanStep completed = PlanStep.pending("collect", "Collect evidence", "llm",
                Map.of(), List.of()).completed("usable evidence");

        StepReflection reflection = ModelStepReflector.standard().reflect(completed,
                new AgentPlan("goal", List.of(completed)), context);

        assertThat(reflection.decision()).isEqualTo(StepReflection.Decision.CONTINUE);
    }

    private static PatternContext context(ModelClient model, DefaultToolRegistry tools) {
        ExecutionContext execution = ExecutionContext.create("reflect-trace");
        return new PatternContext("reflect-task",
                AgentRequest.of("goal", "user", "session", execution), execution,
                model, tools, List.of(), null);
    }

    private static ModelResponse response(String content) {
        return new ModelResponse(content, "stop", null, Map.of());
    }

    private static final class RecordingModel implements ModelClient {
        private final ModelResponse response;
        private ModelRequest request;

        private RecordingModel(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            this.request = request;
            return response;
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    private record NoOpTool(String name) implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "search", "1.0.0", Map.of(), Set.of(),
                    ToolDefinition.Risk.READ_ONLY);
        }

        @Override
        public ToolResult execute(ToolInput input) {
            return ToolResult.success("");
        }
    }
}
