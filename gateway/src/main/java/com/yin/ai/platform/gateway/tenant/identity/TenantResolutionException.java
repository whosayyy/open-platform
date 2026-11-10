package com.yin.ai.platform.gateway.tenant.identity;

public class TenantResolutionException extends RuntimeException {

    private final String authoritativeTenantId;

    public TenantResolutionException(String message, String authoritativeTenantId) {
        super(message);
        this.authoritativeTenantId = authoritativeTenantId;
    }

    public String getAuthoritativeTenantId() {
        return authoritativeTenantId;
    }
}
