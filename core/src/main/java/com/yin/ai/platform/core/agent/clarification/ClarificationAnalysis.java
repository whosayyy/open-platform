package com.yin.ai.platform.core.agent.clarification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Framework-neutral result of analyzing whether a research goal needs user input. */
public record ClarificationAnalysis(
        String clarifiedGoal,
        List<String> questions,
        Map<String, List<String>> options,
        String reason
) {
    public ClarificationAnalysis {
        clarifiedGoal = clarifiedGoal == null || clarifiedGoal.isBlank()
                ? null : clarifiedGoal.trim();
        questions = questions == null ? List.of() : questions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(4).toList();
        options = portableOptions(options, questions);
        reason = reason == null ? "" : reason.trim();
    }

    public static ClarificationAnalysis clarified(String goal) {
        return new ClarificationAnalysis(goal, List.of(), Map.of(), "");
    }

    public static ClarificationAnalysis waiting(List<String> questions,
                                                Map<String, List<String>> options,
                                                String reason) {
        return new ClarificationAnalysis(null, questions, options, reason);
    }

    public boolean waiting() {
        return clarifiedGoal == null && !questions.isEmpty();
    }

    private static Map<String, List<String>> portableOptions(
            Map<String, List<String>> source, List<String> questions) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String question : questions) {
            List<String> values = source.get(question);
            if (values == null) continue;
            List<String> sanitized = values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().limit(4).toList();
            if (!sanitized.isEmpty()) result.put(question, sanitized);
        }
        return Map.copyOf(result);
    }
}
