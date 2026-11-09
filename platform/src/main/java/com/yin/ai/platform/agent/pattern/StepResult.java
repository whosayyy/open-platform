package com.yin.ai.platform.agent.pattern;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Machine-consumable result. User-facing prose belongs in {@link StepSummary}. */
public record StepResult(
        String executionId,
        String stepId,
        boolean success,
        Map<String, Object> value,
        String resultRef,
        List<String> artifactRefs,
        List<String> evidenceRefs,
        String contextRef,
        String error,
        Instant completedAt
) {
    public StepResult {
        value = value == null ? Map.of() : Map.copyOf(value);
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
