package com.yin.ai.platform.gateway.tenant.identity;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTenantResolverTest {

    private final DefaultTenantResolver resolver =
            new DefaultTenantResolver(Optional.of(new SimpleMeterRegistry()));

    @Test
    void resolvesUsingTrustedPrecedenceAndKeepsUserSeparate() {
        ResolvedTenant explicit = resolver.resolve(new TenantResolutionContext(
                "tenant-explicit", null, "tenant-mdc", "user-7", true));
        assertThat(explicit.tenantId()).isEqualTo("tenant-explicit");
        assertThat(explicit.userId()).isEqualTo("user-7");
        assertThat(explicit.source()).isEqualTo(TenantResolutionSource.EXPLICIT);

        ResolvedTenant jwt = resolver.resolve(new TenantResolutionContext(
                null, "tenant-jwt", "tenant-mdc", "user-7", true));
        assertThat(jwt.source()).isEqualTo(TenantResolutionSource.JWT);

        ResolvedTenant mdc = resolver.resolve(new TenantResolutionContext(
                null, null, "tenant-mdc", "user-7", true));
        assertThat(mdc.source()).isEqualTo(TenantResolutionSource.MDC);
    }

    @Test
    void rejectsExplicitJwtConflict() {
        assertThatThrownBy(() -> resolver.resolve(new TenantResolutionContext(
                "forged", "authenticated", null, "user-7", true)))
                .isInstanceOf(TenantResolutionException.class)
                .hasMessageContaining("does not match")
                .extracting(error -> ((TenantResolutionException) error).getAuthoritativeTenantId())
                .isEqualTo("authenticated");
    }

    @Test
    void userFallbackIsExplicitlyMarkedAndCanBeDisabled() {
        ResolvedTenant fallback = resolver.resolve(new TenantResolutionContext(
                null, null, null, "user-7", true));
        assertThat(fallback.compatibilityFallback()).isTrue();
        assertThat(fallback.tenantId()).isEqualTo("user-7");

        assertThat(resolver.resolve(new TenantResolutionContext(
                null, null, null, "user-7", false)).isResolved()).isFalse();
    }
}
