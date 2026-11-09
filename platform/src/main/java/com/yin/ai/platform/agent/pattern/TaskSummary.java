package com.yin.ai.platform.agent.pattern;

import java.util.List;

public record TaskSummary(
        String taskId, String title, String content, List<String> findings,
        List<String> artifactRefs, String nextAction
) {
    public TaskSummary {
        findings = findings == null ? List.of() : List.copyOf(findings);
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    }
}
