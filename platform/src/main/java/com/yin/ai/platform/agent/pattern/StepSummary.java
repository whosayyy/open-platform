package com.yin.ai.platform.agent.pattern;

import java.util.List;

/** Human-readable projection of a {@link StepResult}. */
public record StepSummary(
        String stepId,
        String title,
        String content,
        List<String> findings,
        String nextAction
) {
    public StepSummary {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
