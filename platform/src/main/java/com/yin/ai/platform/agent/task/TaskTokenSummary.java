package com.yin.ai.platform.agent.task;

import java.util.Map;

/** Immutable task token consumption snapshot. */
public record TaskTokenSummary(
        String taskId,
        long totalTokens,
        long totalLlmCalls,
        Map<String, Long> tokensByStep,
        Map<String, Long> callsByStep,
        Map<String, Long> tokensByModel,
        boolean warningThresholdReached,
        boolean budgetExceeded
) {
}
