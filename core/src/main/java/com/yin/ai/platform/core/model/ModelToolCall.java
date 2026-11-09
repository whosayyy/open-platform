package com.yin.ai.platform.core.model;

import java.util.Map;

/** Provider-neutral tool request returned by a model. */
public record ModelToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {
    public ModelToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
