package com.yin.ai.platform.core.agent.checkpoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral, persistence-portable snapshot of an interrupted pattern.
 * State is restricted to maps, lists, strings, booleans and numbers so an
 * adapter never needs to serialize a core implementation class name.
 */
public record AgentCheckpoint(
        String taskId,
        String mode,
        String phase,
        long revision,
        Map<String, Object> state,
        Instant updatedAt
) {
    public static final int SCHEMA_VERSION = 1;

    public AgentCheckpoint {
        taskId = requireText(taskId, "taskId");
        mode = requireText(mode, "mode");
        phase = requireText(phase, "phase");
        revision = Math.max(0, revision);
        state = portableMap(state);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /** Converts this value to infrastructure-neutral scalar collections. */
    public Map<String, Object> toPortableMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("taskId", taskId);
        result.put("mode", mode);
        result.put("phase", phase);
        result.put("revision", revision);
        result.put("state", state);
        result.put("updatedAt", updatedAt.toString());
        return Map.copyOf(result);
    }

    /** Restores a checkpoint from a JSON-decoded or otherwise portable map. */
    public static AgentCheckpoint fromPortableMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) return null;
        Object schema = source.get("schemaVersion");
        if (!(schema instanceof Number number) || number.intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported checkpoint schema: " + schema);
        }
        Object rawState = source.get("state");
        Map<String, Object> state = rawState instanceof Map<?, ?> map
                ? stringKeyMap(map) : Map.of();
        return new AgentCheckpoint(text(source.get("taskId")), text(source.get("mode")),
                text(source.get("phase")), longValue(source.get("revision")), state,
                instant(source.get("updatedAt")));
    }

    private static Map<String, Object> portableMap(Map<String, Object> source) {
        return source == null ? Map.of() : stringKeyMap(source);
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("Checkpoint map keys must be non-blank strings");
            }
            result.put(text, portableValue(value));
        });
        return Map.copyOf(result);
    }

    private static Object portableValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Checkpoint values must omit null entries");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) return stringKeyMap(map);
        if (value instanceof Iterable<?> values) {
            List<Object> result = new ArrayList<>();
            values.forEach(item -> result.add(portableValue(item)));
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("Unsupported checkpoint value: "
                + value.getClass().getName());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Instant instant(Object value) {
        try {
            return value == null ? Instant.now() : Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return Instant.now();
        }
    }
}
