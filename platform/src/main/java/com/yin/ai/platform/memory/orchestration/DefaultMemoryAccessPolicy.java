package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.util.Objects;

/** Deny-by-default tenant/user/scope authorization. */
public class DefaultMemoryAccessPolicy implements MemoryAccessPolicy {
    public static final String ADMIN_PERMISSION = "ai:memory:admin";
    public static final String TENANT_WRITE_PERMISSION = "ai:memory:tenant:write";

    @Override
    public AccessDecision authorizeRead(MemoryScope scope, String scopeId,
                                        String tenantId, String userId,
                                        MemoryAccessContext context) {
        return authorize(scope, scopeId, tenantId, userId, context, false);
    }

    @Override
    public AccessDecision authorizeWrite(MemoryScope scope, String scopeId,
                                         String tenantId, String userId,
                                         MemoryAccessContext context) {
        return authorize(scope, scopeId, tenantId, userId, context, true);
    }

    private AccessDecision authorize(MemoryScope scope, String scopeId,
                                     String tenantId, String userId,
                                     MemoryAccessContext context, boolean write) {
        if (context == null) return AccessDecision.deny("missing access context");
        if (context.hasPermission(ADMIN_PERMISSION)) return AccessDecision.allow();
        if (tenantId == null || !Objects.equals(tenantId, context.tenantId())) {
            return AccessDecision.deny("tenant mismatch");
        }
        if (userId != null && !Objects.equals(userId, context.userId())) {
            return AccessDecision.deny("user mismatch");
        }
        if (scope == null) return AccessDecision.deny("missing scope");
        return switch (scope) {
            case GLOBAL -> AccessDecision.deny("global memory requires admin permission");
            case TENANT -> write && !context.hasPermission(TENANT_WRITE_PERMISSION)
                    ? AccessDecision.deny("tenant write permission required") : AccessDecision.allow();
            case USER -> same(scopeId, context.userId(), "user scope mismatch");
            case SESSION -> same(scopeId, context.sessionId(), "session scope mismatch");
            case TASK -> same(scopeId, context.taskId(), "task scope mismatch");
            case WORKFLOW -> same(scopeId, context.workflowId(), "workflow scope mismatch");
            case AGENT -> same(scopeId, context.agentId(), "agent scope mismatch");
            case STEP -> same(scopeId, context.stepId(), "step scope mismatch");
        };
    }

    private AccessDecision same(String actual, String expected, String reason) {
        return actual != null && Objects.equals(actual, expected)
                ? AccessDecision.allow() : AccessDecision.deny(reason);
    }
}
