package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.plan.AgentPlan;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import com.yin.ai.platform.core.agent.plan.AgentPlanCheckpointCodec;
import com.yin.ai.platform.core.agent.plan.ModelPlanGenerator;
import com.yin.ai.platform.core.agent.plan.PlanExecutionResult;
import com.yin.ai.platform.core.agent.plan.PlanExecutor;
import com.yin.ai.platform.core.agent.plan.PlanGenerator;
import com.yin.ai.platform.core.agent.plan.PlanStep;
import com.yin.ai.platform.core.agent.plan.StepReflector;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Framework-neutral constrained planning, iterative execution and synthesis. */
public final class PlanExecuteAgentPattern implements AgentPattern {

    private final PlanGenerator planner;
    private final PlanExecutor executor;
    private final StepReflector reflector;

    public PlanExecuteAgentPattern(PlanGenerator planner, PlanExecutor executor,
                                   StepReflector reflector) {
        this.planner = planner;
        this.executor = executor;
        this.reflector = reflector == null ? StepReflector.ACCEPT_ALL : reflector;
    }

    public static PlanExecuteAgentPattern standard(PlanExecutor executor) {
        return new PlanExecuteAgentPattern(ModelPlanGenerator.standard(), executor,
                StepReflector.ACCEPT_ALL);
    }

    @Override
    public AgentMode mode() {
        return AgentMode.PLAN_EXECUTE;
    }

    @Override
    public PatternResult execute(PatternContext context) {
        Optional<AgentCheckpoint> saved = matchingCheckpoint(context);
        AgentPlan plan;
        if (saved.isPresent() && saved.get().state().containsKey("plan")) {
            plan = AgentPlanCheckpointCodec.decode(saved.get().state().get("plan"), true);
            context.emit("plan.resumed", Map.of("completedSteps", plan.completedCount(),
                    "totalSteps", plan.steps().size(), "phase", saved.get().phase()));
            context.saveCheckpoint(mode().name(), "executing",
                    stateWithPlan(saved.get().state(), plan));
        } else {
            plan = planner.generate(context.request().goal(), context);
            context.saveCheckpoint(mode().name(), "executing",
                    Map.of("plan", AgentPlanCheckpointCodec.encode(plan)));
        }
        PlanExecutionResult execution = executor.execute(plan, context, reflector);
        if (execution.waiting()) {
            Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
            metadata.put("mode", mode().name());
            return PatternResult.waiting(metadata);
        }
        if (!execution.success()) throw new IllegalStateException(execution.error());

        context.saveCheckpoint(mode().name(), "synthesizing",
                stateWithPlan(context.checkpoint().map(AgentCheckpoint::state).orElse(Map.of()),
                        execution.plan()));
        String output = synthesize(execution.plan(), context);
        context.clearCheckpoint();
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        metadata.put("mode", mode().name());
        metadata.put("plan", execution.plan());
        return new PatternResult(output, metadata);
    }

    private Optional<AgentCheckpoint> matchingCheckpoint(PatternContext context) {
        return context.checkpoint().filter(value -> mode().name().equals(value.mode()));
    }

    private static Map<String, Object> stateWithPlan(Map<String, Object> source,
                                                     AgentPlan plan) {
        Map<String, Object> state = new LinkedHashMap<>(source);
        state.put("plan", AgentPlanCheckpointCodec.encode(plan));
        return Map.copyOf(state);
    }

    private static String synthesize(AgentPlan plan, PatternContext context) {
        List<PlanStep> completed = plan.steps().stream()
                .filter(step -> step.output() != null && !step.output().isBlank()).toList();
        if (completed.isEmpty()) return "";
        if (completed.size() == 1) return completed.getFirst().output();
        StringBuilder findings = new StringBuilder();
        for (PlanStep step : completed) {
            findings.append("\n## ").append(step.description()).append("\n")
                    .append(step.output()).append('\n');
        }
        context.emit("plan.synthesis.started", Map.of("findingCount", completed.size()));
        ModelResponse response = context.callModel(List.of(
                        ModelMessage.system("Synthesize the completed plan into a coherent final answer."),
                        ModelMessage.user("Goal: " + plan.goal() + "\nFindings:" + findings)),
                List.of(), Map.of("operation", "agent.plan.synthesize"));
        return response.content();
    }
}
