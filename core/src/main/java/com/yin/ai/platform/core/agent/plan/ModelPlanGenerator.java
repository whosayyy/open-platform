package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;

import java.util.List;
import java.util.Map;

/** Model-backed constrained planner with a dependency-free JSON parser. */
public final class ModelPlanGenerator implements PlanGenerator {

    private final String planningInstructions;
    private final int maxSteps;

    public ModelPlanGenerator(String planningInstructions, int maxSteps) {
        this.planningInstructions = planningInstructions == null
                ? "Create a concise executable plan." : planningInstructions;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public static ModelPlanGenerator standard() {
        return new ModelPlanGenerator("""
                Create a task-specific execution plan. Use only the available tool names or
                the executor \"llm\". Return JSON only in this shape:
                {"steps":[{"id":"step-1","description":"...","executor":"llm",
                "arguments":{},"dependsOn":[]}]}
                Keep the plan minimal and make dependencies explicit.
                """, 8);
    }

    public static ModelPlanGenerator research() {
        return new ModelPlanGenerator("""
                Create a rigorous research plan with discovery, source verification, analysis,
                and synthesis steps. Use only available tool names or \"llm\". Return JSON only:
                {"steps":[{"id":"step-1","description":"...","executor":"llm",
                "arguments":{},"dependsOn":[]}]}
                """, 12);
    }

    @Override
    public AgentPlan generate(String goal, PatternContext context) {
        String tools = context.availableTools().isEmpty()
                ? "(none)" : String.join(", ", context.availableTools());
        List<ModelMessage> messages = List.of(
                ModelMessage.system(planningInstructions),
                ModelMessage.user("Goal:\n" + goal + "\n\nAvailable tools:\n" + tools));
        context.emit("plan.generation.started", Map.of("goal", goal));
        ModelResponse response = context.callModel(messages, List.of(),
                Map.of("operation", "agent.plan.generate"));
        AgentPlan plan = PlanDocumentParser.parse(response.content(), goal,
                context.availableTools(), maxSteps);
        if (plan == null) {
            plan = new AgentPlan(goal, List.of(PlanStep.pending("step-1", goal,
                    "llm", Map.of(), List.of())));
        }
        context.emit("plan.generated", Map.of("stepCount", plan.steps().size()));
        return plan;
    }
}
