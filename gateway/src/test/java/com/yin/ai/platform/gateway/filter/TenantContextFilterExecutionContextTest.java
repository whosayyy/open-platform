package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.tenant.identity.DefaultTenantResolver;
import com.yin.ai.platform.gateway.tenant.identity.TenantResolutionProperties;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import com.yin.ai.platform.rollout.RolloutMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextFilterExecutionContextTest {

    @Test
    void resolvesTenantAndUserFromCapturedExecutionContextOnWorkerThread() {
        TenantResolutionProperties properties = new TenantResolutionProperties();
        properties.setUserIdFallbackEnabled(false);
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setTenantIsolation(RolloutMode.ENFORCE);
        TenantContextFilter filter = new TenantContextFilter(
                new DefaultTenantResolver(Optional.empty()), properties, rollout);
        ExecutionContext executionContext = new ExecutionContext(
                "trace-1", "session-1", 42L, null, null,
                Map.of("tenantId", "tenant-a"), "agent", "plan", Instant.now(), Map.of());
        GatewayRequest request = new GatewayRequest();

        ExecutionContextHolder.runWith(executionContext, () -> {
            filter.filter(request, ignored -> new GatewayResponse("ok"));
        });

        assertThat(request.getTenantId()).isEqualTo("tenant-a");
        assertThat(request.getUserId()).isEqualTo("42");
    }
}
