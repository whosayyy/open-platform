package com.yin.ai.platform.gateway.tenant.identity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DefaultTenantResolver implements TenantResolver {

    private final Optional<MeterRegistry> meterRegistry;

    public DefaultTenantResolver(Optional<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ResolvedTenant resolve(TenantResolutionContext context) {
        String explicit = normalize(context.explicitTenantId());
        String jwt = normalize(context.jwtTenantId());
        String mdc = normalize(context.mdcTenantId());
        String userId = normalize(context.userId());

        if (jwt != null && explicit != null && !jwt.equals(explicit)) {
            count("conflict", TenantResolutionSource.JWT);
            throw new TenantResolutionException(
                    "Explicit tenant does not match authenticated JWT tenant", jwt);
        }
        if (explicit != null) {
            return resolved(explicit, userId, TenantResolutionSource.EXPLICIT, false);
        }
        if (jwt != null) {
            return resolved(jwt, userId, TenantResolutionSource.JWT, false);
        }
        if (mdc != null) {
            return resolved(mdc, userId, TenantResolutionSource.MDC, false);
        }
        if (context.userIdFallbackEnabled() && userId != null) {
            return resolved(userId, userId, TenantResolutionSource.USER_ID_FALLBACK, true);
        }
        count("unresolved", TenantResolutionSource.UNRESOLVED);
        return ResolvedTenant.unresolved(userId);
    }

    private ResolvedTenant resolved(String tenantId, String userId,
                                    TenantResolutionSource source, boolean fallback) {
        count("resolved", source);
        return new ResolvedTenant(tenantId, userId, source, fallback);
    }

    private void count(String outcome, TenantResolutionSource source) {
        meterRegistry.ifPresent(registry -> Counter.builder("ai.tenant.resolution.total")
                .tag("outcome", outcome)
                .tag("source", source.name().toLowerCase())
                .register(registry)
                .increment());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
