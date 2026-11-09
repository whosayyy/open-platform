package com.yin.ai.platform.core.agent.clarification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Portable description of a research task paused for user clarification. */
public record ClarificationRequest(
        String clarificationId,
        String taskId,
        String originalGoal,
        List<String> questions,
        Map<String, List<String>> options,
        String reason,
        Instant requestedAt
) {
    public static final String ANSWER_PREFIX = "clarification.answer.";

    public ClarificationRequest {
        clarificationId = clarificationId == null || clarificationId.isBlank()
                ? UUID.randomUUID().toString() : clarificationId;
        taskId = requireText(taskId, "taskId");
        originalGoal = requireText(originalGoal, "originalGoal");
        ClarificationAnalysis analysis = ClarificationAnalysis.waiting(
                questions, options, reason);
        questions = analysis.questions();
        options = analysis.options();
        reason = analysis.reason();
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }

    public static ClarificationRequest of(String taskId, String originalGoal,
                                          ClarificationAnalysis analysis) {
        if (analysis == null || !analysis.waiting()) {
            throw new IllegalArgumentException("analysis must require clarification");
        }
        return new ClarificationRequest(null, taskId, originalGoal,
                analysis.questions(), analysis.options(), analysis.reason(), Instant.now());
    }

    public Map<String, Object> toPortableMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clarificationId", clarificationId);
        result.put("taskId", taskId);
        result.put("originalGoal", originalGoal);
        result.put("questions", questions);
        result.put("options", options);
        result.put("reason", reason);
        result.put("requestedAt", requestedAt.toString());
        return Map.copyOf(result);
    }

    public static ClarificationRequest fromPortableMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) return null;
        return new ClarificationRequest(text(source.get("clarificationId")),
                text(source.get("taskId")), text(source.get("originalGoal")),
                stringList(source.get("questions")), options(source.get("options")),
                optionalText(source.get("reason")), instant(source.get("requestedAt")));
    }

    /** Reads current and legacy answer keys without leaking unrelated request metadata. */
    public Map<String, String> answersFrom(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String question : questions) {
            String answer = firstText(metadata.get(question),
                    metadata.get(ANSWER_PREFIX + question));
            if (answer != null) result.put(question, answer);
        }
        String generic = textValue(metadata.get("user_response"));
        if (result.isEmpty() && generic != null) {
            result.put(questions.size() == 1 ? questions.getFirst() : "user_response", generic);
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> options(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), stringList(value));
        });
        return result;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object value : values) {
            String text = textValue(value);
            if (text != null) result.add(text);
        }
        return List.copyOf(result);
    }

    private static Instant instant(Object raw) {
        try {
            return raw == null ? null : Instant.parse(String.valueOf(raw));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstText(String first, String second) {
        String value = textValue(first);
        return value != null ? value : textValue(second);
    }

    private static String textValue(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String optionalText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
