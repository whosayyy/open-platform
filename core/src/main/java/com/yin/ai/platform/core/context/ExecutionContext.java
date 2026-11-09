package com.yin.ai.platform.core.context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, framework-neutral context for a platform operation.
 * Web and security adapters are responsible for creating this value explicitly.
 */
public record ExecutionContext(
        String traceId,
        String conversationId,
        String tenantId,
        String userId,
        String caller,
        String operation,
        Instant createdAt,
        Map<String, Object> attributes,
        CancellationToken cancellationToken
) {
    public ExecutionContext {
        traceId = requireText(traceId, "traceId");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
    }

    public static ExecutionContext create(String traceId) {
        return new ExecutionContext(traceId, null, null, null, null, null,
                Instant.now(), Map.of(), CancellationToken.NONE);
    }

    public ExecutionContext withCaller(String value) {
        return copy(value, operation, attributes);
    }

    public ExecutionContext withOperation(String value) {
        return copy(caller, value, attributes);
    }

    public ExecutionContext withAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> updated = new LinkedHashMap<>(attributes);
        updated.put(key, value);
        return copy(caller, operation, updated);
    }

    private ExecutionContext copy(String newCaller, String newOperation,
                                  Map<String, Object> newAttributes) {
        return new ExecutionContext(traceId, conversationId, tenantId, userId,
                newCaller, newOperation, createdAt, newAttributes, cancellationToken);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
