package com.yin.ai.platform.core.agent.store;

import com.yin.ai.platform.core.agent.AgentInstruction;
import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentStatus;
import com.yin.ai.platform.core.context.CancellationToken;
import com.yin.ai.platform.core.context.ExecutionContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Portable task state owned by the core runtime rather than a persistence adapter. */
public record AgentRunRecord(
        String taskId,
        String parentTaskId,
        AgentRequest request,
        AgentStatus status,
        String mode,
        Instant createdAt,
        Instant updatedAt,
        String output,
        String error,
        List<AgentInstruction> queuedInstructions,
        boolean cancellationRequested,
        long revision
) {
    public static final int SCHEMA_VERSION = 1;

    public AgentRunRecord {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        parentTaskId = clean(parentTaskId);
        Objects.requireNonNull(request, "request");
        status = status == null ? AgentStatus.CREATED : status;
        mode = mode == null || mode.isBlank() ? "DIRECT" : mode.trim();
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        queuedInstructions = queuedInstructions == null
                ? List.of() : List.copyOf(queuedInstructions);
        revision = Math.max(0L, revision);
    }

    /** Converts the record to scalar collections suitable for JSON persistence. */
    public Map<String, Object> toPortableMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("taskId", taskId);
        putIfPresent(result, "parentTaskId", parentTaskId);
        result.put("request", requestMap(request));
        result.put("status", status.name());
        result.put("mode", mode);
        result.put("createdAt", createdAt.toString());
        result.put("updatedAt", updatedAt.toString());
        putIfPresent(result, "output", output);
        putIfPresent(result, "error", error);
        result.put("queuedInstructions", queuedInstructions.stream()
                .map(AgentRunRecord::instructionMap)
                .toList());
        result.put("cancellationRequested", cancellationRequested);
        result.put("revision", revision);
        return Map.copyOf(result);
    }

    /** Restores a record without attempting to persist a process-local cancellation token. */
    public static AgentRunRecord fromPortableMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) return null;
        Object schema = source.get("schemaVersion");
        if (!(schema instanceof Number number) || number.intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported agent run schema: " + schema);
        }
        Map<?, ?> request = map(source.get("request"));
        return new AgentRunRecord(
                text(source.get("taskId")), nullableText(source.get("parentTaskId")),
                request(request), AgentStatus.valueOf(text(source.get("status"))),
                text(source.get("mode")), instant(source.get("createdAt")),
                instant(source.get("updatedAt")), nullableText(source.get("output")),
                nullableText(source.get("error")), instructions(source.get("queuedInstructions")),
                booleanValue(source.get("cancellationRequested")),
                longValue(source.get("revision")));
    }

    private static Map<String, Object> requestMap(AgentRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goal", request.goal());
        putIfPresent(result, "userId", request.userId());
        putIfPresent(result, "sessionId", request.sessionId());
        result.put("metadata", request.metadata());
        putIfPresent(result, "systemPrompt", request.systemPrompt());
        putIfPresent(result, "temperature", request.temperature());
        putIfPresent(result, "model", request.model());
        if (request.executionContext() != null) {
            result.put("executionContext", contextMap(request.executionContext()));
        }
        return Map.copyOf(result);
    }

    private static AgentRequest request(Map<?, ?> source) {
        Object temperature = source.get("temperature");
        return new AgentRequest(text(source.get("goal")), nullableText(source.get("userId")),
                nullableText(source.get("sessionId")), stringMap(source.get("metadata")),
                nullableText(source.get("systemPrompt")),
                temperature instanceof Number number ? number.doubleValue() : null,
                nullableText(source.get("model")), context(source.get("executionContext")));
    }

    private static Map<String, Object> contextMap(ExecutionContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", context.traceId());
        putIfPresent(result, "conversationId", context.conversationId());
        putIfPresent(result, "tenantId", context.tenantId());
        putIfPresent(result, "userId", context.userId());
        putIfPresent(result, "caller", context.caller());
        putIfPresent(result, "operation", context.operation());
        result.put("createdAt", context.createdAt().toString());
        result.put("attributes", portableMap(context.attributes()));
        return Map.copyOf(result);
    }

    private static ExecutionContext context(Object value) {
        if (value == null) return null;
        Map<?, ?> source = map(value);
        return new ExecutionContext(text(source.get("traceId")),
                nullableText(source.get("conversationId")), nullableText(source.get("tenantId")),
                nullableText(source.get("userId")), nullableText(source.get("caller")),
                nullableText(source.get("operation")), instant(source.get("createdAt")),
                objectMap(source.get("attributes")), CancellationToken.NONE);
    }

    private static Map<String, Object> instructionMap(AgentInstruction instruction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", instruction.type());
        putIfPresent(result, "content", instruction.content());
        result.put("metadata", portableMap(instruction.metadata()));
        return Map.copyOf(result);
    }

    private static List<AgentInstruction> instructions(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<AgentInstruction> result = new ArrayList<>();
        for (Object item : values) {
            Map<?, ?> source = map(item);
            result.add(new AgentInstruction(text(source.get("type")),
                    nullableText(source.get("content")), objectMap(source.get("metadata"))));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(text(key), text(item)));
        return Map.copyOf(result);
    }

    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> source ? portableMap(source) : Map.of();
    }

    private static Map<String, Object> portableMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(text(key), portableValue(value)));
        return Map.copyOf(result);
    }

    private static Object portableValue(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) return portableMap(map);
        if (value instanceof Iterable<?> values) {
            List<Object> result = new ArrayList<>();
            values.forEach(item -> result.add(portableValue(item)));
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("Unsupported agent run value: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static Map<?, ?> map(Object value) {
        if (value instanceof Map<?, ?> source) return source;
        throw new IllegalArgumentException("Expected an agent run object");
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(nullableText(value));
    }

    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Required agent run value is missing");
        }
        return value.toString();
    }

    private static String nullableText(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
