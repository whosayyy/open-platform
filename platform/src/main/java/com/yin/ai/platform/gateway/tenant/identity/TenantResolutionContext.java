package com.yin.ai.platform.gateway.tenant.identity;

public record TenantResolutionContext(
        String explicitTenantId,
        String jwtTenantId,
        String mdcTenantId,
        String userId,
        boolean userIdFallbackEnabled) {
}
