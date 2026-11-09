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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPlanExecutorTest {

    @Test
    void reflectionCanRetryAndThenExtendThePlan() {
        AtomicInteger toolCalls = new AtomicInteger();
        DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(new CountingTool(toolCalls));
        PatternContext context = context(tools);
        AgentPlan plan = new AgentPlan("goal", List.of(PlanStep.pending(
                "first", "first step", "count", Map.of("value", "one"), List.of())));
        AtomicInteger reflections = new AtomicInteger();
        StepReflector reflector = (step, current, ignored) -> switch (reflections.incrementAndGet()) {
            case 1 -> new StepReflection(StepReflection.Decision.RETRY,
                    "improve", "try again", List.of());
            case 2 -> new StepReflection(StepReflection.Decision.REPLAN,
                    "need another source", null, List.of(PlanStep.pending(
                    "second", "second step", "count", Map.of("value", "two"),
                    List.of("first"))));
            default -> StepReflection.continueExecution();
        };

        PlanExecutionResult result = new DefaultPlanExecutor(8, 2)
                .execute(plan, context, reflector);

        assertTrue(result.success());
        assertEquals(2, result.plan().steps().size());
        assertEquals(2, result.plan().completedCount());
        assertEquals(3, toolCalls.get());
        assertEquals(3, result.rounds());
    }

    @Test
    void planRejectsCyclesAtTheCoreBoundary() {
        PlanStep first = PlanStep.pending("first", "first", "llm", Map.of(), List.of("second"));
        PlanStep second = PlanStep.pending("second", "second", "llm", Map.of(), List.of("first"));

        assertThrows(IllegalArgumentException.class,
                () -> new AgentPlan("goal", List.of(first, second)));
    }

    private static PatternContext context(DefaultToolRegistry tools) {
        ExecutionContext execution = ExecutionContext.create("trace-plan");
        AgentRequest request = AgentRequest.of("goal", "user", "session", execution);
        ModelClient unusedModel = new ModelClient() {
            @Override
            public ModelResponse execute(ModelRequest request, ExecutionContext context) {
                throw new AssertionError("Model should not be called");
            }

            @Override
            public ModelCall submit(ModelRequest request, ExecutionContext context,
                                    ModelEventListener listener) {
                throw new UnsupportedOperationException();
            }
        };
        return new PatternContext("task-1", request, execution, unusedModel, tools,
                List.of(), null);
    }

    private record CountingTool(AtomicInteger calls) implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("count", "Count calls", "1.0.0", Map.of(),
                    Set.of(), ToolDefinition.Risk.READ_ONLY);
        }

        @Override
        public ToolResult execute(ToolInput input) {
            calls.incrementAndGet();
            return ToolResult.success(input.parameters().get("value"));
        }
    }
}
