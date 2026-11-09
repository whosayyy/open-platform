package com.yin.ai.platform.core.model;

import java.util.Map;

/** Provider-neutral chat message. */
public record ModelMessage(ModelRole role, String content, String name,
                           Map<String, Object> metadata) {
    public ModelMessage {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(ModelRole.USER, content, null, Map.of());
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(ModelRole.SYSTEM, content, null, Map.of());
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(ModelRole.ASSISTANT, content, null, Map.of());
    }

    public static ModelMessage tool(String content, String name, String toolCallId) {
        return new ModelMessage(ModelRole.TOOL, content, name,
                toolCallId == null || toolCallId.isBlank()
                        ? Map.of() : Map.of("toolCallId", toolCallId));
    }
}
