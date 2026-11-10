package com.yin.ai.platform.gateway;

import com.yin.ai.platform.core.agent.pattern.ReasoningMode;

/** Provider-neutral execution options resolved once for a model call. */
public record ModelExecutionOptions(
        ReasoningMode requestedReasoning,
        ReasoningMode effectiveReasoning,
        String fallbackReason
) {
    public ModelExecutionOptions {
        requestedReasoning = requestedReasoning == null ? ReasoningMode.AUTO : requestedReasoning;
        effectiveReasoning = effectiveReasoning == null ? ReasoningMode.OFF : effectiveReasoning;
    }

    public boolean downgraded() {
        return fallbackReason != null && !fallbackReason.isBlank();
    }
}
