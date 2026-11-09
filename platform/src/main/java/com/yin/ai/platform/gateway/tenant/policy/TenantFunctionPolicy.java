package com.yin.ai.platform.gateway.tenant.policy;

import java.time.Instant;
import java.util.Set;

public record TenantFunctionPolicy(
        String tenantId,
        Set<String> allowedFunctions,
        long version,
        Instant updatedAt) {

    public TenantFunctionPolicy {
        allowedFunctions = allowedFunctions == null ? Set.of() : Set.copyOf(allowedFunctions);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
