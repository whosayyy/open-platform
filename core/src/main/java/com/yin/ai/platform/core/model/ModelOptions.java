package com.yin.ai.platform.core.model;

import java.util.Map;

/** Common options; provider-specific values remain in extensions. */
public record ModelOptions(
        String model,
        Double temperature,
        Integer maxOutputTokens,
        Map<String, Object> extensions
) {
    public ModelOptions {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static ModelOptions defaults() {
        return new ModelOptions(null, null, null, Map.of());
    }
}
