package com.yin.ai.platform.core.tool;

import com.yin.ai.platform.core.context.ExecutionContext;

import java.util.Map;

/** Tool parameters plus explicit execution context. */
public record ToolInput(
        Map<String, Object> parameters,
        String requestId,
        ExecutionContext executionContext
) {
    public ToolInput {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
