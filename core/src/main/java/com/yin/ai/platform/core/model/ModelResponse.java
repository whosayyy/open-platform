package com.yin.ai.platform.core.model;

import java.util.List;
import java.util.Map;

/** Final provider-neutral model response. */
public record ModelResponse(
        String content,
        String finishReason,
        ModelUsage usage,
        Map<String, Object> metadata,
        List<ModelToolCall> toolCalls
) {
    public ModelResponse {
        content = content == null ? "" : content;
        usage = usage == null ? ModelUsage.empty() : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** Compatibility constructor for model adapters that do not expose tool calls. */
    public ModelResponse(String content, String finishReason, ModelUsage usage,
                         Map<String, Object> metadata) {
        this(content, finishReason, usage, metadata, List.of());
    }
}
