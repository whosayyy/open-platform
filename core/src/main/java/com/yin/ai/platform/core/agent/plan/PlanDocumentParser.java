package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.json.PortableJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small internal JSON reader that keeps core independent from serialization libraries. */
final class PlanDocumentParser {

    private PlanDocumentParser() { }

    static AgentPlan parse(String content, String goal, List<String> availableTools,
                           int maxSteps) {
        try {
            Object root = PortableJson.parseDocument(content);
            if (root == null) return null;
            List<?> definitions = findSteps(root);
            if (definitions == null || definitions.isEmpty()) return null;
            Set<String> allowed = new LinkedHashSet<>(availableTools == null
                    ? List.of() : availableTools);
            List<PlanStep> provisional = new ArrayList<>();
            int index = 1;
            for (Object value : definitions) {
                if (!(value instanceof Map<?, ?> definition)) continue;
                String description = text(first(definition, "description", "task", "title"));
                if (description == null || description.isBlank()) continue;
                String id = text(definition.get("id"));
                if (id == null || id.isBlank()) id = "step-" + index;
                String executor = text(first(definition, "executor", "tool"));
                if (executor == null || executor.isBlank() || "llm".equalsIgnoreCase(executor)) {
                    executor = "llm";
                } else if (!allowed.contains(executor)) {
                    executor = "llm";
                }
                provisional.add(PlanStep.pending(id, description, executor,
                        objectMap(definition.get("arguments")),
                        stringList(first(definition, "dependsOn", "depends_on", "dependencies"))));
                index++;
                if (provisional.size() >= maxSteps) break;
            }
            if (provisional.isEmpty()) return null;
            return new AgentPlan(goal, sanitizeDependencies(provisional));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<PlanStep> sanitizeDependencies(List<PlanStep> steps) {
        Set<String> ids = new LinkedHashSet<>();
        List<PlanStep> unique = new ArrayList<>();
        for (PlanStep step : steps) {
            if (ids.add(step.id())) unique.add(step);
        }
        List<PlanStep> result = new ArrayList<>();
        for (PlanStep step : unique) {
            List<String> dependencies = step.dependsOn().stream()
                    .filter(ids::contains)
                    .filter(id -> !id.equals(step.id()))
                    .distinct().toList();
            result.add(PlanStep.pending(step.id(), step.description(), step.executor(),
                    step.arguments(), dependencies));
        }
        return result;
    }

    private static List<?> findSteps(Object root) {
        if (root instanceof List<?> list) return list;
        if (!(root instanceof Map<?, ?> map)) return null;
        Object value = first(map, "steps", "items", "tasks");
        if (value instanceof List<?> list) return list;
        Object plan = map.get("plan");
        if (plan instanceof List<?> list) return list;
        if (plan instanceof Map<?, ?> nested) return findSteps(nested);
        return null;
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null).map(String::valueOf)
                .filter(item -> !item.isBlank()).toList();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

}
