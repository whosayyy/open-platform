package com.yin.ai.platform.core.agent.clarification;

import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.json.PortableJson;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dependency-free model-backed clarification strategy used by Deep Research. */
public final class ModelResearchClarifier implements ResearchClarifier {

    public static ModelResearchClarifier standard() {
        return new ModelResearchClarifier();
    }

    @Override
    public ClarificationAnalysis analyze(String goal, PatternContext context) {
        try {
            ModelResponse response = context.callModel(List.of(
                    ModelMessage.system("""
                            Analyze whether a research request has a critical ambiguity.
                            Prefer reasonable assumptions over interrupting the user.
                            Return JSON only. If clear enough:
                            {"action":"clarified","goal":"precise research goal"}
                            If user input is essential:
                            {"action":"ask","questions":["..."],
                             "options":{"question":["choice"]},"reason":"..."}
                            Ask at most four concise questions with at most four options each.
                            """),
                    ModelMessage.user("Research request:\n" + goal)), List.of(),
                    Map.of("operation", "agent.research.clarify"));
            return parse(response.content(), goal);
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            return ClarificationAnalysis.clarified(goal);
        }
    }

    @Override
    public String merge(ClarificationRequest request, Map<String, String> answers,
                        PatternContext context) {
        if (answers == null || answers.isEmpty()) return request.originalGoal();
        String formatted = formatAnswers(answers);
        try {
            ModelResponse response = context.callModel(List.of(
                    ModelMessage.system("""
                            Merge the original research request and the user's clarification
                            into one precise research goal. Preserve constraints and scope.
                            Return only the merged goal, without commentary.
                            """),
                    ModelMessage.user("Original goal:\n" + request.originalGoal()
                            + "\n\nUser clarification:\n" + formatted)), List.of(),
                    Map.of("operation", "agent.research.clarification.merge",
                            "clarificationId", request.clarificationId()));
            String merged = response.content();
            return merged == null || merged.isBlank()
                    ? deterministicMerge(request.originalGoal(), formatted) : merged.trim();
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            return deterministicMerge(request.originalGoal(), formatted);
        }
    }

    private static ClarificationAnalysis parse(String content, String fallbackGoal) {
        try {
            Object document = PortableJson.parseDocument(content);
            if (!(document instanceof Map<?, ?> map)) {
                return ClarificationAnalysis.clarified(fallbackGoal);
            }
            String action = text(map.get("action"));
            if ("ask".equals(action.toLowerCase(Locale.ROOT))) {
                List<String> questions = strings(map.get("questions"));
                if (!questions.isEmpty()) {
                    return ClarificationAnalysis.waiting(questions,
                            options(map.get("options")), text(map.get("reason")));
                }
            }
            String goal = text(map.get("goal"));
            return ClarificationAnalysis.clarified(
                    goal.isBlank() ? fallbackGoal : goal);
        } catch (RuntimeException ignored) {
            return ClarificationAnalysis.clarified(fallbackGoal);
        }
    }

    private static Map<String, List<String>> options(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), strings(value));
        });
        return result;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private static String formatAnswers(Map<String, String> answers) {
        StringBuilder result = new StringBuilder();
        answers.forEach((question, answer) -> result.append("- ").append(question)
                .append(": ").append(answer).append('\n'));
        return result.toString().trim();
    }

    private static String deterministicMerge(String goal, String formattedAnswers) {
        return goal + "\n\nUser clarification:\n" + formattedAnswers;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
