package com.yin.ai.platform.core.agent.plan;

import java.util.List;
import java.util.Map;

/** Immutable unit in a plan DAG. Empty executor means a model-only step. */
public record PlanStep(
        String id,
        String description,
        String executor,
        Map<String, Object> arguments,
        List<String> dependsOn,
        PlanStepStatus status,
        String output,
        String error
) {
    public PlanStep {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        status = status == null ? PlanStepStatus.PENDING : status;
    }

    public static PlanStep pending(String id, String description, String executor,
                                   Map<String, Object> arguments, List<String> dependsOn) {
        return new PlanStep(id, description, executor, arguments, dependsOn,
                PlanStepStatus.PENDING, null, null);
    }

    public PlanStep withStatus(PlanStepStatus value) {
        return new PlanStep(id, description, executor, arguments, dependsOn,
                value, output, error);
    }

    public PlanStep completed(String value) {
        return new PlanStep(id, description, executor, arguments, dependsOn,
                PlanStepStatus.COMPLETED, value, null);
    }

    public PlanStep failed(String value) {
        return new PlanStep(id, description, executor, arguments, dependsOn,
                PlanStepStatus.FAILED, output, value);
    }
}
