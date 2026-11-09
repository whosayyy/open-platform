package com.yin.ai.platform.core.agent.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encodes plans as scalar collections and restores interrupted steps safely. */
public final class AgentPlanCheckpointCodec {

    private AgentPlanCheckpointCodec() {
    }

    public static Map<String, Object> encode(AgentPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goal", plan.goal());
        result.put("steps", plan.steps().stream().map(AgentPlanCheckpointCodec::encodeStep).toList());
        return Map.copyOf(result);
    }

    public static AgentPlan decode(Object raw, boolean resetInterrupted) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Checkpoint plan must be a map");
        }
        Object rawSteps = map.get("steps");
        if (!(rawSteps instanceof Iterable<?> values)) {
            throw new IllegalArgumentException("Checkpoint plan steps must be a list");
        }
        List<PlanStep> steps = new ArrayList<>();
        for (Object value : values) steps.add(decodeStep(value, resetInterrupted));
        return new AgentPlan(text(map.get("goal")), steps);
    }

    private static Map<String, Object> encodeStep(PlanStep step) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", step.id());
        result.put("description", step.description());
        result.put("executor", step.executor() == null ? "" : step.executor());
        result.put("arguments", step.arguments());
        result.put("dependsOn", step.dependsOn());
        result.put("status", step.status().name());
        if (step.output() != null) result.put("output", step.output());
        if (step.error() != null) result.put("error", step.error());
        return Map.copyOf(result);
    }

    private static PlanStep decodeStep(Object raw, boolean resetInterrupted) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Checkpoint plan step must be a map");
        }
        PlanStepStatus status = PlanStepStatus.valueOf(text(map.get("status")));
        boolean interrupted = resetInterrupted && (status == PlanStepStatus.RUNNING
                || status == PlanStepStatus.FAILED);
        if (interrupted) {
            status = PlanStepStatus.PENDING;
        }
        return new PlanStep(text(map.get("id")), text(map.get("description")),
                blankToNull(nullableText(map.get("executor"))), objectMap(map.get("arguments")),
                stringList(map.get("dependsOn")), status,
                nullableText(map.get("output")),
                interrupted ? null : nullableText(map.get("error")));
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(String.valueOf(value)));
        return List.copyOf(result);
    }

    private static String nullableText(Object value) {
        return value == null ? null : value.toString();
    }

    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Checkpoint plan text must not be blank");
        }
        return value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
