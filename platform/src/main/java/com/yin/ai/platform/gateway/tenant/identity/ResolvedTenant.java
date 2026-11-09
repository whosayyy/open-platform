package com.yin.ai.platform.gateway.tenant.identity;

public record ResolvedTenant(
        String tenantId,
        String userId,
        TenantResolutionSource source,
        boolean compatibilityFallback) {

    public static ResolvedTenant unresolved(String userId) {
        return new ResolvedTenant(null, userId, TenantResolutionSource.UNRESOLVED, false);
    }

    public boolean isResolved() {
        return tenantId != null && !tenantId.isBlank();
    }
}
