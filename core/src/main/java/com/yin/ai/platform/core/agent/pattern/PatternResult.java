package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.AgentStatus;

import java.util.Map;

/** Completed or externally paused output of a core orchestration policy. */
public record PatternResult(String output, Map<String, Object> metadata, AgentStatus status) {
    public PatternResult(String output, Map<String, Object> metadata) {
        this(output, metadata, AgentStatus.COMPLETED);
    }

    public PatternResult {
        output = output == null ? "" : output;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        status = status == AgentStatus.WAITING ? AgentStatus.WAITING : AgentStatus.COMPLETED;
    }

    public static PatternResult waiting(Map<String, Object> metadata) {
        return new PatternResult("", metadata, AgentStatus.WAITING);
    }
}
