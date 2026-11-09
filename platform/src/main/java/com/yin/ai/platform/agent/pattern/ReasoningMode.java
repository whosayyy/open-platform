package com.yin.ai.platform.agent.pattern;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** User-visible reasoning strength, independent from the agent execution mode. */
public enum ReasoningMode {
    OFF,
    AUTO,
    LOW,
    MEDIUM,
    HIGH;

    @JsonCreator
    public static ReasoningMode fromString(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
