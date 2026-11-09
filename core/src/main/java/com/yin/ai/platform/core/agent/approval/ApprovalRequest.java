package com.yin.ai.platform.core.agent.approval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Portable description of a plan step waiting for an external decision. */
public record ApprovalRequest(
        String approvalId,
        String taskId,
        String stepId,
        String action,
        String rationale,
        ApprovalRisk risk,
        Map<String, Object> context,
        Instant requestedAt,
        Instant expiresAt
) {
    public ApprovalRequest {
        approvalId = approvalId == null || approvalId.isBlank()
                ? UUID.randomUUID().toString() : approvalId;
        taskId = requireText(taskId, "taskId");
        stepId = requireText(stepId, "stepId");
        action = requireText(action, "action");
        rationale = rationale == null ? "" : rationale;
        risk = risk == null ? ApprovalRisk.MEDIUM : risk;
        context = context == null ? Map.of() : Map.copyOf(context);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }

    public static ApprovalRequest of(String taskId, String stepId, String action,
                                     String rationale, ApprovalRisk risk,
                                     Map<String, Object> context) {
        return new ApprovalRequest(null, taskId, stepId, action, rationale, risk,
                context, Instant.now(), null);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && (now == null ? Instant.now() : now).isAfter(expiresAt);
    }

    /** Converts the request into scalar collections suitable for any adapter. */
    public Map<String, Object> toPortableMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvalId", approvalId);
        result.put("taskId", taskId);
        result.put("stepId", stepId);
        result.put("action", action);
        result.put("rationale", rationale);
        result.put("risk", risk.name());
        result.put("context", context);
        result.put("requestedAt", requestedAt.toString());
        if (expiresAt != null) result.put("expiresAt", expiresAt.toString());
        return Map.copyOf(result);
    }

    /** Restores a request from a JSON-decoded checkpoint map. */
    public static ApprovalRequest fromPortableMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) return null;
        return new ApprovalRequest(text(source.get("approvalId")),
                text(source.get("taskId")), text(source.get("stepId")),
                text(source.get("action")), optionalText(source.get("rationale")),
                risk(source.get("risk")), objectMap(source.get("context")),
                instant(source.get("requestedAt")), nullableInstant(source.get("expiresAt")));
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    private static ApprovalRisk risk(Object value) {
        try {
            return value == null ? ApprovalRisk.MEDIUM
                    : ApprovalRisk.valueOf(value.toString());
        } catch (IllegalArgumentException ignored) {
            return ApprovalRisk.MEDIUM;
        }
    }

    private static Instant instant(Object value) {
        Instant parsed = nullableInstant(value);
        return parsed == null ? Instant.now() : parsed;
    }

    private static Instant nullableInstant(Object value) {
        try {
            return value == null || value.toString().isBlank()
                    ? null : Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String optionalText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
