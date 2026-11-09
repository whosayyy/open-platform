package com.yin.ai.platform.core.tool;

import java.util.Map;
import java.util.Set;

/** Protocol-neutral tool metadata and JSON-schema-like input description. */
public record ToolDefinition(
        String name,
        String description,
        String version,
        Map<String, Object> inputSchema,
        Set<String> tags,
        Risk risk
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
        version = version == null || version.isBlank() ? "1.0.0" : version;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        risk = risk == null ? Risk.UNSPECIFIED : risk;
    }

    public enum Risk {
        READ_ONLY,
        WRITE,
        DESTRUCTIVE,
        UNSPECIFIED
    }
}
