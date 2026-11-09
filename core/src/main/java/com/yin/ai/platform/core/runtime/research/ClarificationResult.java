package com.yin.ai.platform.core.runtime.research;

import java.util.List;
import java.util.Map;

/** Framework-neutral result of a research clarification phase. */
public record ClarificationResult(
        boolean needed,
        List<String> questions,
        Map<String, List<String>> options,
        String clarifiedGoal
) {
    public static ClarificationResult notNeeded(String goal) {
        return new ClarificationResult(false, List.of(), Map.of(), goal);
    }

    public static ClarificationResult waiting(List<String> questions) {
        return new ClarificationResult(true, questions, Map.of(), null);
    }

    public static ClarificationResult waiting(List<String> questions,
                                              Map<String, List<String>> options) {
        return new ClarificationResult(true, questions, options != null ? options : Map.of(), null);
    }

    public boolean isWaiting() {
        return needed && clarifiedGoal == null;
    }
}
