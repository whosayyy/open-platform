package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.adapter.memory.context.LegacyMemoryContextMapper;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.core.memory.MemoryContext;

import java.util.Map;
import java.util.Set;

/** Complete identity and scope context used by memory authorization. */
public record MemoryAccessContext(
        String tenantId,
        String userId,
        String sessionId,
        String taskId,
        String workflowId,
        String agentId,
        String stepId,
        Set<String> roles,
        Set<String> permissions,
        String traceId) {

    public MemoryAccessContext {
        roles = roles != null ? Set.copyOf(roles) : Set.of();
        permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
    }

    public static MemoryAccessContext from(MemoryContext context,
                                           boolean userAsTenantFallback) {
        if (context == null) return empty();
        Map<String, Object> attributes = context.attributes();
        String userId = context.userId() != null ? context.userId().toString() : null;
        String tenantId = stringValue(attributes.get("tenantId"));
        if (tenantId == null) tenantId = stringValue(org.slf4j.MDC.get("tenantId"));
        if (tenantId == null && userAsTenantFallback) tenantId = userId;
        return new MemoryAccessContext(tenantId, userId,
                firstNonBlank(stringValue(attributes.get("sessionId")), context.conversationId()),
                stringValue(attributes.get("taskId")), stringValue(attributes.get("workflowId")),
                context.agentId(), stringValue(attributes.get("stepId")),
                stringSet(attributes.get("roles")), stringSet(attributes.get("permissions")),
                context.traceId());
    }

    public static MemoryAccessContext from(ExecutionContext context,
                                           boolean userAsTenantFallback) {
        if (context == null) return empty();
        MemoryAccessContext base = from(LegacyMemoryContextMapper.from(context),
                userAsTenantFallback);
        Set<String> roles = context.loginUser() != null
                ? nullSafe(context.loginUser().getRoles()) : Set.of();
        Set<String> permissions = context.loginUser() != null
                ? nullSafe(context.loginUser().getPermissions()) : Set.of();
        return new MemoryAccessContext(base.tenantId, base.userId, base.sessionId,
                base.taskId, base.workflowId, base.agentId, base.stepId,
                roles, permissions, base.traceId);
    }

    public static MemoryAccessContext empty() {
        return new MemoryAccessContext(null, null, null, null, null, null,
                null, Set.of(), Set.of(), null);
    }

    public boolean hasPermission(String permission) {
        return permission != null && permissions.contains(permission);
    }

    private static String stringValue(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return Set.of();
        java.util.HashSet<String> result = new java.util.HashSet<>();
        iterable.forEach(item -> { if (item != null) result.add(item.toString()); });
        return Set.copyOf(result);
    }

    private static Set<String> nullSafe(Set<String> values) {
        return values != null ? Set.copyOf(values) : Set.of();
    }
}
