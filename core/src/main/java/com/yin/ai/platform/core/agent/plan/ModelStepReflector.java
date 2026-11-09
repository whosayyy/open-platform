package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.json.PortableJson;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dependency-free model-backed quality gate for completed plan steps. */
public final class ModelStepReflector implements StepReflector {

    private static final int MAX_OUTPUT_CHARS = 6_000;
    private static final int MAX_NEW_STEPS = 4;

    public static ModelStepReflector standard() {
        return new ModelStepReflector();
    }

    @Override
    public StepReflection reflect(PlanStep completedStep, AgentPlan plan,
                                  PatternContext context) {
        if (completedStep.output() == null || completedStep.output().isBlank()) {
            return new StepReflection(StepReflection.Decision.RETRY,
                    "Step output is empty", "Return a concrete result for the requested step",
                    List.of());
        }
        try {
            ModelResponse response = context.callModel(List.of(
                    ModelMessage.system("""
                            Evaluate the quality of one completed research step.
                            Return JSON only with this shape:
                            {"decision":"CONTINUE|RETRY|REPLAN|SKIP_TO_SYNTHESIS|ABORT",
                             "reason":"...","retryHint":"...","newSteps":[
                               {"id":"...","description":"...","executor":"llm or tool name",
                                "dependsOn":["existing-step-id"]}]}
                            Prefer CONTINUE when the output is usable. Use RETRY only for a clear
                            quality defect, and REPLAN only when essential evidence is missing.
                            Add at most four steps and use only the available executors.
                            """),
                    ModelMessage.user(prompt(completedStep, plan, context))), List.of(),
                    Map.of("operation", "agent.plan.reflect", "stepId", completedStep.id()));
            StepReflection reflection = parse(response.content(), plan, context);
            context.emit("plan.step.reflected", Map.of(
                    "stepId", completedStep.id(),
                    "decision", reflection.decision().name(),
                    "newStepCount", reflection.newSteps().size()));
            return reflection;
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            return StepReflection.continueExecution();
        }
    }

    private static String prompt(PlanStep step, AgentPlan plan, PatternContext context) {
        String output = step.output().length() <= MAX_OUTPUT_CHARS
                ? step.output() : step.output().substring(0, MAX_OUTPUT_CHARS);
        return "Goal: " + plan.goal()
                + "\nCompleted step: " + step.description()
                + "\nAvailable executors: llm, " + String.join(", ", context.availableTools())
                + "\nStep output:\n" + output;
    }

    private static StepReflection parse(String content, AgentPlan plan,
                                        PatternContext context) {
        Object document = PortableJson.parseDocument(content);
        if (!(document instanceof Map<?, ?> map)) {
            return StepReflection.continueExecution();
        }
        StepReflection.Decision decision = decision(text(map.get("decision")));
        String reason = optionalText(map.get("reason"));
        String retryHint = firstText(map.get("retryHint"), map.get("hint"));
        List<PlanStep> newSteps = decision == StepReflection.Decision.REPLAN
                ? newSteps(map.get("newSteps"), plan, context) : List.of();
        if (decision == StepReflection.Decision.REPLAN && newSteps.isEmpty()) {
            return StepReflection.continueExecution();
        }
        return new StepReflection(decision, reason, retryHint, newSteps);
    }

    private static StepReflection.Decision decision(String raw) {
        if (raw == null) return StepReflection.Decision.CONTINUE;
        try {
            return StepReflection.Decision.valueOf(raw.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return StepReflection.Decision.CONTINUE;
        }
    }

    private static List<PlanStep> newSteps(Object raw, AgentPlan plan,
                                           PatternContext context) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<Map<?, ?>> definitions = new ArrayList<>();
        Set<String> existingIds = new LinkedHashSet<>();
        plan.steps().forEach(step -> existingIds.add(step.id()));
        Set<String> newIds = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> definition)) continue;
            String id = text(definition.get("id"));
            if (id == null || existingIds.contains(id) || !newIds.add(id)) continue;
            definitions.add(definition);
            if (definitions.size() >= MAX_NEW_STEPS) break;
        }
        Set<String> allowedDependencies = new LinkedHashSet<>(existingIds);
        allowedDependencies.addAll(newIds);
        Set<String> availableExecutors = new LinkedHashSet<>(context.availableTools());
        availableExecutors.add("llm");
        List<PlanStep> result = new ArrayList<>();
        for (Map<?, ?> definition : definitions) {
            String id = text(definition.get("id"));
            String description = text(definition.get("description"));
            String executor = canonicalExecutor(text(definition.get("executor")),
                    availableExecutors);
            if (description == null || executor == null) {
                continue;
            }
            List<String> dependencies = strings(definition.get("dependsOn")).stream()
                    .filter(allowedDependencies::contains)
                    .filter(value -> !id.equals(value)).distinct().toList();
            result.add(PlanStep.pending(id, description, executor,
                    objectMap(definition.get("arguments")), dependencies));
        }
        try {
            plan.append(result);
            return List.copyOf(result);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static String canonicalExecutor(String requested, Set<String> availableExecutors) {
        if (requested == null) return null;
        if ("llm".equalsIgnoreCase(requested)) return "llm";
        return availableExecutors.stream()
                .filter(value -> value.equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return Map.copyOf(result);
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = text(value);
            if (text != null) result.add(text);
        }
        return List.copyOf(result);
    }

    private static String firstText(Object first, Object second) {
        String value = text(first);
        return value != null ? value : optionalText(second);
    }

    private static String optionalText(Object value) {
        String text = text(value);
        return text == null ? "" : text;
    }

    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }
}
