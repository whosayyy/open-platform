package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.approval.ApprovalContext;
import com.yin.ai.platform.core.agent.approval.ApprovalDecision;
import com.yin.ai.platform.core.agent.approval.ApprovalRequest;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Core iterative Plan/Execute state machine with retry, reflection and replanning. */
public final class DefaultPlanExecutor implements PlanExecutor {

    private final int maxRounds;
    private final int maxRetriesPerStep;

    public DefaultPlanExecutor(int maxRounds, int maxRetriesPerStep) {
        this.maxRounds = Math.max(1, maxRounds);
        this.maxRetriesPerStep = Math.max(0, maxRetriesPerStep);
    }

    public static DefaultPlanExecutor standard() {
        return new DefaultPlanExecutor(32, 2);
    }

    @Override
    public PlanExecutionResult execute(AgentPlan initialPlan, PatternContext context,
                                       StepReflector reflector) {
        AgentPlan plan = initialPlan;
        StepReflector qualityGate = reflector == null ? StepReflector.ACCEPT_ALL : reflector;
        Map<String, Integer> attempts = checkpointAttempts(context);
        Map<String, String> retryHints = checkpointRetryHints(context);
        Set<String> approvedSteps = checkpointApprovedSteps(context);
        int rounds = checkpointRounds(context);

        while (!plan.allDone() && rounds < maxRounds) {
            context.executionContext().cancellationToken().throwIfCancellationRequested();
            List<PlanStep> ready = plan.readySteps();
            if (ready.isEmpty()) {
                return failed(plan, "Plan has unresolved or cyclic dependencies", rounds);
            }
            PlanStep step = ready.getFirst();
            ApprovalGate approval = approvalGate(context, plan, step,
                    plan.steps().indexOf(step), approvedSteps);
            plan = approval.plan();
            if (approval.decision() == ApprovalGateDecision.WAIT) {
                return waiting(plan, approval.request(), rounds);
            }
            if (approval.decision() == ApprovalGateDecision.SKIP) {
                saveProgress(context, plan, attempts, retryHints, rounds);
                continue;
            }
            rounds++;
            int attempt = attempts.merge(step.id(), 1, Integer::sum);
            plan = plan.update(step.withStatus(PlanStepStatus.RUNNING));
            saveProgress(context, plan, attempts, retryHints, rounds);
            context.emit("plan.step.started", Map.of("stepId", step.id(),
                    "description", step.description(), "round", rounds, "attempt", attempt));

            StepOutcome outcome = executeStep(step, plan, retryHints.get(step.id()), context);
            if (!outcome.success()) {
                if (attempt <= maxRetriesPerStep) {
                    plan = plan.update(step.withStatus(PlanStepStatus.PENDING));
                    retryHints.put(step.id(), outcome.error());
                    saveProgress(context, plan, attempts, retryHints, rounds);
                    context.emit("plan.step.retrying", Map.of("stepId", step.id(),
                            "attempt", attempt, "error", safe(outcome.error())));
                    continue;
                }
                plan = plan.update(step.failed(outcome.error()));
                saveProgress(context, plan, attempts, retryHints, rounds);
                context.emit("plan.step.failed", Map.of("stepId", step.id(),
                        "error", safe(outcome.error())));
                return failed(plan, "Step " + step.id() + " failed: " + outcome.error(), rounds);
            }

            PlanStep completed = step.completed(outcome.output());
            plan = plan.update(completed);
            retryHints.remove(step.id());
            saveProgress(context, plan, attempts, retryHints, rounds);
            context.emit("plan.step.completed", Map.of("stepId", step.id(),
                    "output", safe(outcome.output())));

            StepReflection reflection = qualityGate.reflect(completed, plan, context);
            if (reflection == null) reflection = StepReflection.continueExecution();
            switch (reflection.decision()) {
                case CONTINUE -> { }
                case RETRY -> {
                    if (attempt <= maxRetriesPerStep) {
                        plan = plan.update(completed.withStatus(PlanStepStatus.PENDING));
                        retryHints.put(step.id(), safe(reflection.retryHint()));
                    }
                }
                case REPLAN -> {
                    plan = plan.append(reflection.newSteps());
                    context.emit("plan.replanned", Map.of("addedSteps",
                            reflection.newSteps().size(), "reason", safe(reflection.reason())));
                }
                case SKIP_TO_SYNTHESIS -> plan = skipPending(plan);
                case ABORT -> {
                    saveProgress(context, plan, attempts, retryHints, rounds);
                    return failed(plan, "Execution aborted: " + safe(reflection.reason()), rounds);
                }
            }
            saveProgress(context, plan, attempts, retryHints, rounds);
        }

        if (!plan.allDone()) return failed(plan, "Plan exceeded maximum rounds", rounds);
        saveProgress(context, plan, attempts, retryHints, rounds);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rounds", rounds);
        metadata.put("completedSteps", plan.completedCount());
        metadata.put("totalSteps", plan.steps().size());
        return new PlanExecutionResult(true, plan, null, rounds, metadata);
    }

    private static void saveProgress(PatternContext context, AgentPlan plan,
                                     Map<String, Integer> attempts,
                                     Map<String, String> retryHints, int rounds) {
        context.checkpoint().ifPresent(current -> {
            Map<String, Object> state = new LinkedHashMap<>(current.state());
            state.put("plan", AgentPlanCheckpointCodec.encode(plan));
            state.put("attempts", Map.copyOf(attempts));
            state.put("retryHints", Map.copyOf(retryHints));
            state.put("rounds", rounds);
            context.saveCheckpoint(current.mode(), current.phase(), state);
        });
    }

    private static Map<String, Integer> checkpointAttempts(PatternContext context) {
        Object raw = checkpointState(context, "attempts");
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (value instanceof Number number) {
                    result.put(String.valueOf(key), Math.max(0, number.intValue()));
                }
            });
        }
        return result;
    }

    private static Map<String, String> checkpointRetryHints(PatternContext context) {
        Object raw = checkpointState(context, "retryHints");
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> values) {
            values.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        }
        return result;
    }

    private static int checkpointRounds(PatternContext context) {
        Object value = checkpointState(context, "rounds");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static Object checkpointState(PatternContext context, String key) {
        return context.checkpoint().map(AgentCheckpoint::state)
                .map(state -> state.get(key)).orElse(null);
    }

    private static Set<String> checkpointApprovedSteps(PatternContext context) {
        Object raw = checkpointState(context, "approvedSteps");
        Set<String> result = new LinkedHashSet<>();
        if (raw instanceof Iterable<?> values) {
            values.forEach(value -> result.add(String.valueOf(value)));
        }
        return result;
    }

    private ApprovalGate approvalGate(PatternContext context, AgentPlan plan,
                                      PlanStep step, int stepIndex,
                                      Set<String> approvedSteps) {
        Optional<ApprovalRequest> pending = checkpointApproval(context);
        if (pending.isPresent()) {
            ApprovalRequest request = pending.get();
            if (!step.id().equals(request.stepId())) {
                return new ApprovalGate(ApprovalGateDecision.WAIT, plan, request);
            }
            if (request.isExpired(Instant.now())) {
                AgentPlan skipped = plan.update(step.withStatus(PlanStepStatus.SKIPPED));
                clearPendingApproval(context, skipped, approvedSteps);
                emitApprovalResolved(context, request, "expired", "system");
                context.emit("plan.step.skipped", Map.of("stepId", step.id(),
                        "reason", "approval expired"));
                return new ApprovalGate(ApprovalGateDecision.SKIP, skipped, request);
            }
            String decisionId = context.request().metadata().get("approvalId");
            Optional<ApprovalDecision> decision = ApprovalDecision.parse(
                    context.request().metadata().get("approvalDecision"));
            if (!request.approvalId().equals(decisionId) || decision.isEmpty()) {
                return new ApprovalGate(ApprovalGateDecision.WAIT, plan, request);
            }
            String resolvedBy = context.request().metadata().getOrDefault(
                    "approvalResolvedBy", "user");
            if (decision.get() == ApprovalDecision.REJECT) {
                AgentPlan skipped = plan.update(step.withStatus(PlanStepStatus.SKIPPED));
                clearPendingApproval(context, skipped, approvedSteps);
                emitApprovalResolved(context, request, "reject", resolvedBy);
                context.emit("plan.step.skipped", Map.of("stepId", step.id(),
                        "reason", "approval rejected"));
                return new ApprovalGate(ApprovalGateDecision.SKIP, skipped, request);
            }
            approvedSteps.add(step.id());
            clearPendingApproval(context, plan, approvedSteps);
            emitApprovalResolved(context, request, "approve", resolvedBy);
            return new ApprovalGate(ApprovalGateDecision.EXECUTE, plan, request);
        }

        if (approvedSteps.contains(step.id())) {
            return new ApprovalGate(ApprovalGateDecision.EXECUTE, plan, null);
        }
        Optional<ApprovalRequest> requested = context.approvals().evaluate(
                new ApprovalContext(context.taskId(), checkpointMode(context), plan, step,
                        stepIndex, context.executionContext()));
        if (requested == null || requested.isEmpty()) {
            return new ApprovalGate(ApprovalGateDecision.EXECUTE, plan, null);
        }
        ApprovalRequest request = requested.get();
        if (!context.taskId().equals(request.taskId()) || !step.id().equals(request.stepId())) {
            throw new IllegalArgumentException("Approval request does not match current task step");
        }
        savePendingApproval(context, plan, request, approvedSteps);
        context.emit("approval.required", request.toPortableMap());
        return new ApprovalGate(ApprovalGateDecision.WAIT, plan, request);
    }

    private static Optional<ApprovalRequest> checkpointApproval(PatternContext context) {
        Object raw = checkpointState(context, "approval");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Optional.empty();
        return Optional.of(ApprovalRequest.fromPortableMap(map));
    }

    private static String checkpointMode(PatternContext context) {
        return context.checkpoint().map(AgentCheckpoint::mode).orElse("PLAN_EXECUTE");
    }

    private static void savePendingApproval(PatternContext context, AgentPlan plan,
                                            ApprovalRequest request,
                                            Set<String> approvedSteps) {
        context.checkpoint().ifPresent(current -> {
            Map<String, Object> state = new LinkedHashMap<>(current.state());
            state.put("plan", AgentPlanCheckpointCodec.encode(plan));
            state.put("approval", request.toPortableMap());
            state.put("approvedSteps", List.copyOf(approvedSteps));
            context.saveCheckpoint(current.mode(), "waiting_approval", state);
        });
    }

    private static void clearPendingApproval(PatternContext context, AgentPlan plan,
                                             Set<String> approvedSteps) {
        context.checkpoint().ifPresent(current -> {
            Map<String, Object> state = new LinkedHashMap<>(current.state());
            state.put("plan", AgentPlanCheckpointCodec.encode(plan));
            state.remove("approval");
            state.put("approvedSteps", List.copyOf(approvedSteps));
            context.saveCheckpoint(current.mode(), "executing", state);
        });
    }

    private static void emitApprovalResolved(PatternContext context, ApprovalRequest request,
                                             String decision, String resolvedBy) {
        context.emit("approval.resolved", Map.of(
                "approvalId", request.approvalId(),
                "stepId", request.stepId(),
                "decision", decision,
                "resolvedBy", resolvedBy));
    }

    private static PlanExecutionResult waiting(AgentPlan plan, ApprovalRequest request,
                                               int rounds) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phase", "approval");
        metadata.put("rounds", rounds);
        metadata.put("approval", request.toPortableMap());
        return new PlanExecutionResult(false, true, plan, null, rounds, metadata);
    }

    private StepOutcome executeStep(PlanStep step, AgentPlan plan, String retryHint,
                                    PatternContext context) {
        String executor = step.executor();
        if (executor != null && !executor.isBlank() && !"llm".equalsIgnoreCase(executor)) {
            Tool tool = context.toolRegistry().find(executor).orElse(null);
            if (tool == null) return StepOutcome.failed("Unknown tool: " + executor);
            context.emit("tool.started", Map.of("stepId", step.id(), "tool", executor));
            try {
                ToolResult result = tool.execute(new ToolInput(step.arguments(),
                        context.taskId() + ":" + step.id(), context.executionContext()));
                if (result == null) return StepOutcome.failed("Tool returned no result: " + executor);
                if (!result.success()) {
                    return StepOutcome.failed(result.errorMessage() == null
                            ? result.errorCode() : result.errorMessage());
                }
                String output = stringify(result.data());
                context.emit("tool.completed", Map.of("stepId", step.id(), "tool", executor));
                return StepOutcome.success(output);
            } catch (RuntimeException exception) {
                return StepOutcome.failed(exception.getMessage());
            }
        }

        String prompt = buildStepPrompt(step, plan, retryHint);
        ModelResponse response = context.callModel(List.of(
                        ModelMessage.system("Execute one plan step. Return only the step result."),
                        ModelMessage.user(prompt)), List.of(),
                Map.of("operation", "agent.plan.step", "stepId", step.id()));
        return StepOutcome.success(response.content());
    }

    private static String buildStepPrompt(PlanStep step, AgentPlan plan, String retryHint) {
        StringBuilder prompt = new StringBuilder("Goal: ").append(plan.goal())
                .append("\nCurrent step: ").append(step.description());
        List<PlanStep> dependencies = plan.steps().stream()
                .filter(candidate -> step.dependsOn().contains(candidate.id()))
                .toList();
        if (!dependencies.isEmpty()) {
            prompt.append("\nDependency outputs:");
            for (PlanStep dependency : dependencies) {
                prompt.append("\n- ").append(dependency.id()).append(": ")
                        .append(safe(dependency.output()));
            }
        }
        if (retryHint != null && !retryHint.isBlank()) {
            prompt.append("\nRetry guidance: ").append(retryHint);
        }
        return prompt.toString();
    }

    private static AgentPlan skipPending(AgentPlan plan) {
        AgentPlan current = plan;
        for (PlanStep step : plan.steps()) {
            if (step.status() == PlanStepStatus.PENDING) {
                current = current.update(step.withStatus(PlanStepStatus.SKIPPED));
            }
        }
        return current;
    }

    private static PlanExecutionResult failed(AgentPlan plan, String error, int rounds) {
        return new PlanExecutionResult(false, plan, error, rounds,
                Map.of("rounds", rounds, "completedSteps", plan.completedCount(),
                        "totalSteps", plan.steps().size()));
    }

    private static String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        return String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record StepOutcome(boolean success, String output, String error) {
        static StepOutcome success(String output) { return new StepOutcome(true, output, null); }
        static StepOutcome failed(String error) { return new StepOutcome(false, null, safe(error)); }
    }

    private enum ApprovalGateDecision { EXECUTE, SKIP, WAIT }

    private record ApprovalGate(ApprovalGateDecision decision, AgentPlan plan,
                                ApprovalRequest request) { }
}
