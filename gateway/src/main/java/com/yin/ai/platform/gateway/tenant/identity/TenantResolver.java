package com.yin.ai.platform.gateway.tenant.identity;

public interface TenantResolver {

    ResolvedTenant resolve(TenantResolutionContext context);
}
