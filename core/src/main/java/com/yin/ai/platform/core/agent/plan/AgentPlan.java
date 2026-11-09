package com.yin.ai.platform.core.agent.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable plan whose dependencies form a validated directed acyclic graph. */
public record AgentPlan(String goal, List<PlanStep> steps) {
    public AgentPlan {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal must not be blank");
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("steps must not be empty");
        validate(steps);
    }

    public long completedCount() {
        return steps.stream().filter(step -> step.status() == PlanStepStatus.COMPLETED).count();
    }

    public boolean allDone() {
        return steps.stream().allMatch(step -> step.status() == PlanStepStatus.COMPLETED
                || step.status() == PlanStepStatus.SKIPPED);
    }

    public AgentPlan update(PlanStep replacement) {
        return new AgentPlan(goal, steps.stream()
                .map(step -> step.id().equals(replacement.id()) ? replacement : step).toList());
    }

    public AgentPlan append(List<PlanStep> additions) {
        if (additions == null || additions.isEmpty()) return this;
        List<PlanStep> merged = new ArrayList<>(steps);
        merged.addAll(additions);
        return new AgentPlan(goal, merged);
    }

    public List<PlanStep> readySteps() {
        Set<String> complete = new HashSet<>();
        for (PlanStep step : steps) {
            if (step.status() == PlanStepStatus.COMPLETED
                    || step.status() == PlanStepStatus.SKIPPED) complete.add(step.id());
        }
        return steps.stream()
                .filter(step -> step.status() == PlanStepStatus.PENDING)
                .filter(step -> complete.containsAll(step.dependsOn()))
                .toList();
    }

    private static void validate(List<PlanStep> steps) {
        Set<String> ids = new HashSet<>();
        for (PlanStep step : steps) {
            if (!ids.add(step.id())) throw new IllegalArgumentException("Duplicate plan step: " + step.id());
        }
        for (PlanStep step : steps) {
            for (String dependency : step.dependsOn()) {
                if (!ids.contains(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency " + dependency
                            + " for step " + step.id());
                }
                if (step.id().equals(dependency)) {
                    throw new IllegalArgumentException("Step cannot depend on itself: " + step.id());
                }
            }
        }
        Set<String> resolved = new HashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (PlanStep step : steps) {
                if (!resolved.contains(step.id()) && resolved.containsAll(step.dependsOn())) {
                    resolved.add(step.id());
                    progressed = true;
                }
            }
        } while (progressed);
        if (resolved.size() != steps.size()) {
            throw new IllegalArgumentException("Plan contains cyclic dependencies");
        }
    }
}
