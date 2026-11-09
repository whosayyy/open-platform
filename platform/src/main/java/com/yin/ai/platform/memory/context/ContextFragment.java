package com.yin.ai.platform.memory.context;

import java.util.Map;

public record ContextFragment(String provider, String content, int tokenCount,
                              Map<String, Object> metadata) {
    public ContextFragment {
        content = content != null ? content : "";
        tokenCount = Math.max(0, tokenCount);
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
