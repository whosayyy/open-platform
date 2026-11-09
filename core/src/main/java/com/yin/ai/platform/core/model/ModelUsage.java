package com.yin.ai.platform.core.model;

/** Provider-neutral token accounting. */
public record ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
    public ModelUsage {
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        totalTokens = Math.max(Math.max(0, totalTokens), inputTokens + outputTokens);
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0);
    }
}
