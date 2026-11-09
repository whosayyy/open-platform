package com.yin.ai.platform.core.agent.pattern;

import java.util.Locale;

/** Framework-neutral execution modes supported by the core runtime. */
public enum AgentMode {
    DIRECT,
    REACT,
    PLAN_EXECUTE,
    DEEP_RESEARCH;

    public static AgentMode parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "simple", "direct", "chat" -> DIRECT;
            case "react", "re_act" -> REACT;
            case "plan", "plan_execute" -> PLAN_EXECUTE;
            case "deep", "research", "deep_research" -> DEEP_RESEARCH;
            default -> throw new IllegalArgumentException("Unknown agent mode: " + value);
        };
    }
}
