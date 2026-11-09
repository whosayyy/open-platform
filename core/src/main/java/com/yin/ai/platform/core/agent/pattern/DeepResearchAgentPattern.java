package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.clarification.ClarificationAnalysis;
import com.yin.ai.platform.core.agent.clarification.ClarificationRequest;
import com.yin.ai.platform.core.agent.clarification.ModelResearchClarifier;
import com.yin.ai.platform.core.agent.clarification.ResearchClarifier;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import com.yin.ai.platform.core.agent.plan.AgentPlan;
import com.yin.ai.platform.core.agent.plan.AgentPlanCheckpointCodec;
import com.yin.ai.platform.core.agent.plan.ModelPlanGenerator;
import com.yin.ai.platform.core.agent.plan.PlanExecutionResult;
import com.yin.ai.platform.core.agent.plan.PlanExecutor;
import com.yin.ai.platform.core.agent.plan.PlanGenerator;
import com.yin.ai.platform.core.agent.plan.PlanStep;
import com.yin.ai.platform.core.agent.plan.ModelStepReflector;
import com.yin.ai.platform.core.agent.plan.StepReflector;
import com.yin.ai.platform.core.agent.research.DefaultResearchSynthesisPolicy;
import com.yin.ai.platform.core.agent.research.ResearchSource;
import com.yin.ai.platform.core.agent.research.ResearchSynthesisPolicy;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Four-phase core research workflow: clarify, plan, iterate, synthesize. */
public final class DeepResearchAgentPattern implements AgentPattern {

    private final PlanGenerator planner;
    private final PlanExecutor executor;
    private final StepReflector reflector;
    private final ResearchClarifier clarifier;
    private final ResearchSynthesisPolicy synthesisPolicy;

    public DeepResearchAgentPattern(PlanGenerator planner, PlanExecutor executor,
                                    StepReflector reflector) {
        this(planner, executor, reflector, ModelResearchClarifier.standard(),
                DefaultResearchSynthesisPolicy.standard());
    }

    public DeepResearchAgentPattern(PlanGenerator planner, PlanExecutor executor,
                                    StepReflector reflector, ResearchClarifier clarifier) {
        this(planner, executor, reflector, clarifier,
                DefaultResearchSynthesisPolicy.standard());
    }

    public DeepResearchAgentPattern(PlanGenerator planner, PlanExecutor executor,
                                    StepReflector reflector, ResearchClarifier clarifier,
                                    ResearchSynthesisPolicy synthesisPolicy) {
        this.planner = planner;
        this.executor = executor;
        this.reflector = reflector == null ? StepReflector.ACCEPT_ALL : reflector;
        this.clarifier = clarifier == null ? ModelResearchClarifier.standard() : clarifier;
        this.synthesisPolicy = synthesisPolicy == null
                ? DefaultResearchSynthesisPolicy.standard() : synthesisPolicy;
    }

    public static DeepResearchAgentPattern standard(PlanExecutor executor) {
        return standard(executor, ModelResearchClarifier.standard());
    }

    public static DeepResearchAgentPattern standard(PlanExecutor executor,
                                                    ResearchClarifier clarifier) {
        return standard(executor, clarifier, ModelStepReflector.standard(),
                DefaultResearchSynthesisPolicy.standard());
    }

    public static DeepResearchAgentPattern standard(PlanExecutor executor,
                                                     ResearchClarifier clarifier,
                                                     StepReflector reflector,
                                                     ResearchSynthesisPolicy synthesisPolicy) {
        return new DeepResearchAgentPattern(ModelPlanGenerator.research(), executor,
                reflector, clarifier, synthesisPolicy);
    }

    @Override
    public AgentMode mode() {
        return AgentMode.DEEP_RESEARCH;
    }

    @Override
    public PatternResult execute(PatternContext context) {
        Optional<AgentCheckpoint> saved = matchingCheckpoint(context);
        String clarifiedGoal;
        AgentPlan plan;
        if (saved.isPresent() && saved.get().state().containsKey("plan")) {
            clarifiedGoal = String.valueOf(saved.get().state().getOrDefault(
                    "clarifiedGoal", clarifiedGoal(context)));
            plan = AgentPlanCheckpointCodec.decode(saved.get().state().get("plan"), true);
            context.emit("research.resumed", Map.of("completedSteps", plan.completedCount(),
                    "totalSteps", plan.steps().size(), "phase", saved.get().phase()));
            context.saveCheckpoint(mode().name(), "executing",
                    stateWithPlan(saved.get().state(), plan, clarifiedGoal));
        } else {
            GoalResolution clarification = resolveGoal(context, saved);
            if (clarification.waiting()) {
                return waitingForClarification(clarification.request());
            }
            clarifiedGoal = clarification.goal();
            context.emit("research.clarification.completed",
                    Map.of("clarifiedGoal", clarifiedGoal));

            context.emit("research.planning.started", Map.of());
            plan = planner.generate(clarifiedGoal, context);
            context.saveCheckpoint(mode().name(), "executing",
                    stateWithPlan(saved.map(AgentCheckpoint::state).orElse(Map.of()),
                            plan, clarifiedGoal));
        }
        context.emit("research.execution.started", Map.of("stepCount", plan.steps().size()));
        PlanExecutionResult execution = executor.execute(plan, context, reflector);
        if (execution.waiting()) {
            Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
            metadata.put("mode", mode().name());
            metadata.put("clarifiedGoal", clarifiedGoal);
            return PatternResult.waiting(metadata);
        }
        if (!execution.success()) throw new IllegalStateException(execution.error());

        context.saveCheckpoint(mode().name(), "synthesizing",
                stateWithPlan(context.checkpoint().map(AgentCheckpoint::state).orElse(Map.of()),
                        execution.plan(), clarifiedGoal));
        ResearchSynthesisResult synthesis = synthesize(execution.plan(), context);
        context.clearCheckpoint();
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        metadata.put("mode", mode().name());
        metadata.put("phase", "completed");
        metadata.put("plan", execution.plan());
        metadata.put("clarifiedGoal", clarifiedGoal);
        metadata.put("verifiedSourceCount", synthesis.verifiedSourceCount());
        metadata.put("removedUnverifiedSourceCount", synthesis.removedSourceCount());
        return new PatternResult(synthesis.report(), metadata);
    }

    private Optional<AgentCheckpoint> matchingCheckpoint(PatternContext context) {
        return context.checkpoint().filter(value -> mode().name().equals(value.mode()));
    }

    private GoalResolution resolveGoal(PatternContext context,
                                       Optional<AgentCheckpoint> saved) {
        String supplied = context.request().metadata().get("clarifiedGoal");
        if (supplied != null && !supplied.isBlank()) {
            String resolved = supplied.trim();
            saveClarifiedGoal(context, saved, resolved);
            return GoalResolution.resolved(resolved);
        }
        String checkpointGoal = saved.map(AgentCheckpoint::state)
                .map(state -> state.get("clarifiedGoal"))
                .map(String::valueOf).filter(value -> !value.isBlank()).orElse(null);
        if (checkpointGoal != null) {
            return GoalResolution.resolved(checkpointGoal.trim());
        }

        Optional<ClarificationRequest> pending = saved.flatMap(checkpoint ->
                clarification(checkpoint.state().get("clarification")));
        if (pending.isPresent()) {
            ClarificationRequest request = pending.get();
            String suppliedId = context.request().metadata().get("clarificationId");
            if (suppliedId != null && !suppliedId.isBlank()
                    && !request.clarificationId().equals(suppliedId)) {
                context.emit("clarification.required", request.toPortableMap());
                return GoalResolution.waiting(request);
            }
            Map<String, String> answers = request.answersFrom(context.request().metadata());
            if (answers.isEmpty()) {
                context.emit("clarification.required", request.toPortableMap());
                return GoalResolution.waiting(request);
            }
            String resolved = clarifier.merge(request, answers, context);
            if (resolved == null || resolved.isBlank()) resolved = request.originalGoal();
            context.emit("clarification.resolved", Map.of(
                    "clarificationId", request.clarificationId(),
                    "answerCount", answers.size()));
            resolved = resolved.trim();
            saveClarifiedGoal(context, saved, resolved);
            return GoalResolution.resolved(resolved);
        }

        String originalGoal = context.request().goal().trim();
        ClarificationAnalysis analysis = clarifier.analyze(originalGoal, context);
        if (analysis == null || !analysis.waiting()) {
            String resolved = analysis == null ? null : analysis.clarifiedGoal();
            resolved = resolved == null || resolved.isBlank()
                    ? originalGoal : resolved.trim();
            saveClarifiedGoal(context, saved, resolved);
            return GoalResolution.resolved(resolved);
        }

        ClarificationRequest request = ClarificationRequest.of(
                context.taskId(), originalGoal, analysis);
        Map<String, Object> state = saved.map(AgentCheckpoint::state)
                .map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
        state.put("clarification", request.toPortableMap());
        context.saveCheckpoint(mode().name(), "waiting_clarification", state);
        context.emit("clarification.required", request.toPortableMap());
        return GoalResolution.waiting(request);
    }

    private static void saveClarifiedGoal(PatternContext context,
                                          Optional<AgentCheckpoint> saved,
                                          String clarifiedGoal) {
        Map<String, Object> state = saved.map(AgentCheckpoint::state)
                .map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
        state.remove("clarification");
        state.put("clarifiedGoal", clarifiedGoal);
        context.saveCheckpoint(AgentMode.DEEP_RESEARCH.name(), "planning", state);
    }

    private static Optional<ClarificationRequest> clarification(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Optional.empty();
        return Optional.ofNullable(ClarificationRequest.fromPortableMap(map));
    }

    private PatternResult waitingForClarification(ClarificationRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode().name());
        metadata.put("phase", "clarification");
        metadata.put("clarification", request.toPortableMap());
        return PatternResult.waiting(metadata);
    }

    private static Map<String, Object> stateWithPlan(Map<String, Object> source,
                                                     AgentPlan plan,
                                                     String clarifiedGoal) {
        Map<String, Object> state = new LinkedHashMap<>(source);
        state.remove("clarification");
        state.put("plan", AgentPlanCheckpointCodec.encode(plan));
        state.put("clarifiedGoal", clarifiedGoal);
        return Map.copyOf(state);
    }

    private static String clarifiedGoal(PatternContext context) {
        String supplied = context.request().metadata().get("clarifiedGoal");
        return supplied == null || supplied.isBlank()
                ? context.request().goal().trim() : supplied.trim();
    }

    private ResearchSynthesisResult synthesize(AgentPlan plan, PatternContext context) {
        StringBuilder evidence = new StringBuilder();
        for (PlanStep step : plan.steps()) {
            if (step.output() == null || step.output().isBlank()) continue;
            evidence.append("\n## ").append(step.description()).append("\n")
                    .append(step.output()).append('\n');
        }
        List<ResearchSource> sources = synthesisPolicy.collectSources(plan);
        context.emit("research.synthesis.started", Map.of(
                "evidenceSteps", plan.completedCount(),
                "verifiedSourceCount", sources.size()));
        ModelResponse response = context.callModel(List.of(
                        ModelMessage.system("""
                                Produce a structured research report from the supplied evidence.
                                Separate findings from uncertainty, preserve source references, and
                                never invent a source that is absent from the verified source list.
                                """),
                        ModelMessage.user("Research goal: " + plan.goal()
                                + "\nVerified execution evidence:" + evidence
                                + "\nVerified source list:\n"
                                + synthesisPolicy.formatSources(sources))),
                List.of(), Map.of("operation", "agent.research.synthesize"));
        ResearchSynthesisPolicy.Verification verification =
                synthesisPolicy.verify(response.content(), sources);
        context.emit("research.synthesis.completed", Map.of(
                "verified", verification.verified(),
                "removedUnverifiedSourceCount", verification.unverifiedUrls().size()));
        return new ResearchSynthesisResult(verification.report(), sources.size(),
                verification.unverifiedUrls().size());
    }

    private record GoalResolution(String goal, ClarificationRequest request) {
        static GoalResolution resolved(String goal) {
            return new GoalResolution(goal, null);
        }

        static GoalResolution waiting(ClarificationRequest request) {
            return new GoalResolution(null, request);
        }

        boolean waiting() {
            return request != null;
        }
    }

    private record ResearchSynthesisResult(String report, int verifiedSourceCount,
                                           int removedSourceCount) { }
}
