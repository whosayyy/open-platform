package com.yin.ai.platform.core.tool;

import java.util.Map;

/** Stable tool result independent of Spring AI ToolCallback. */
public record ToolResult(
        boolean success,
        Object data,
        String errorCode,
        String errorMessage,
        Map<String, Object> metadata
) {
    public ToolResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolResult success(Object data) {
        return new ToolResult(true, data, null, null, Map.of());
    }

    public static ToolResult failure(String code, String message) {
        return new ToolResult(false, null, code, message, Map.of());
    }
}
