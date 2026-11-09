package com.yin.ai.platform.gateway.tenant.identity;

public enum TenantResolutionSource {
    EXPLICIT,
    JWT,
    MDC,
    USER_ID_FALLBACK,
    UNRESOLVED
}
