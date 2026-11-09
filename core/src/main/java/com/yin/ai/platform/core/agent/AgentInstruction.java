package com.yin.ai.platform.core.agent;

import java.util.Map;

/** Instruction queued for a running or waiting task. */
public record AgentInstruction(String type, String content, Map<String, Object> metadata) {
    public AgentInstruction {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
