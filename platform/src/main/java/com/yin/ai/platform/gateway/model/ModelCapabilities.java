package com.yin.ai.platform.gateway.model;

import com.yin.ai.platform.agent.pattern.ReasoningMode;

import java.util.EnumSet;
import java.util.Set;

/** Typed model capabilities used before provider-specific option mapping. */
public record ModelCapabilities(
        boolean reasoning,
        Set<ReasoningMode> reasoningModes,
        boolean toolCalling,
        boolean streaming,
        boolean structuredOutput,
        int maxContextTokens
) {
    public ModelCapabilities {
        reasoningModes = reasoningModes == null || reasoningModes.isEmpty()
                ? Set.of(ReasoningMode.OFF)
                : Set.copyOf(reasoningModes);
    }

    public static ModelCapabilities standard() {
        return new ModelCapabilities(false, Set.of(ReasoningMode.OFF), true, true, true, 64_000);
    }

    public static ModelCapabilities reasoning(Set<ReasoningMode> modes, int maxContextTokens) {
        EnumSet<ReasoningMode> normalized = EnumSet.of(ReasoningMode.OFF);
        if (modes != null) normalized.addAll(modes);
        normalized.remove(ReasoningMode.AUTO);
        return new ModelCapabilities(true, normalized, true, true, true, maxContextTokens);
    }

    public boolean supports(ReasoningMode mode) {
        return mode == ReasoningMode.OFF || (reasoning && reasoningModes.contains(mode));
    }
}
