package com.yin.ai.platform.core.agent.approval;

import java.util.Locale;
import java.util.Optional;

/** Explicit decision supplied when a paused core step is resumed. */
public enum ApprovalDecision {
    APPROVE,
    REJECT;

    public static Optional<ApprovalDecision> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
