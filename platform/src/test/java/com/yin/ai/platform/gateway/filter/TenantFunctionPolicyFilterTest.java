package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.tenant.policy.InMemoryTenantFunctionPolicyStore;
import com.yin.ai.platform.gateway.tenant.policy.TenantFunctionPolicy;
import com.yin.ai.platform.gateway.tenant.policy.TenantPolicyProperties;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import com.yin.ai.platform.rollout.RolloutMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantFunctionPolicyFilterTest {

    @Test
    void enforceUsesPlatformTenantIntersectionAndIncludesForcedTool() {
        InMemoryTenantFunctionPolicyStore store = new InMemoryTenantFunctionPolicyStore();
        store.savePolicy(new TenantFunctionPolicy("t1", Set.of("searchNews"), 1, Instant.now()));
        TenantPolicyProperties properties = new TenantPolicyProperties();
        properties.setPlatformAllowedFunctions(Set.of("searchNews", "readWebPage"));
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setTenantIsolation(RolloutMode.ENFORCE);
        TenantFunctionPolicyFilter filter = new TenantFunctionPolicyFilter(
                store, properties, rollout, Optional.empty());

        GatewayRequest allowed = request("t1", Set.of("searchNews"));
        assertThatCode(() -> filter.authorize(allowed)).doesNotThrowAnyException();

        GatewayRequest denied = request("t1", Set.of("readWebPage"));
        assertThatThrownBy(() -> filter.authorize(denied))
                .isInstanceOf(TenantFunctionPolicyFilter.FunctionAccessDeniedException.class);

        GatewayRequest forced = request("t1", Set.of());
        forced.getOptions().setForcedToolName("readWebPage");
        assertThatThrownBy(() -> filter.authorize(forced))
                .isInstanceOf(TenantFunctionPolicyFilter.FunctionAccessDeniedException.class);
    }

    @Test
    void shadowModeRecordsDecisionWithoutBreakingRequest() {
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setTenantIsolation(RolloutMode.SHADOW);
        TenantFunctionPolicyFilter filter = new TenantFunctionPolicyFilter(
                new InMemoryTenantFunctionPolicyStore(), new TenantPolicyProperties(),
                rollout, Optional.empty());
        assertThatCode(() -> filter.authorize(request("t1", Set.of("unknown"))))
                .doesNotThrowAnyException();
    }

    private GatewayRequest request(String tenantId, Set<String> functions) {
        GatewayRequest request = new GatewayRequest(List.of(), new GatewayOptions());
        request.setTenantId(tenantId);
        request.setFunctionNames(functions);
        return request;
    }
}
