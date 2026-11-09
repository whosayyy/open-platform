package com.yin.ai.platform.core.model;

import java.util.Map;

/** Delta or lifecycle signal emitted by an asynchronous model call. */
public record ModelEvent(String type, String delta, Map<String, Object> metadata) {
    public ModelEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        delta = delta == null ? "" : delta;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
