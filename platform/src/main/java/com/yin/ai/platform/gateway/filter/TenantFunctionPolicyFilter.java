package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import com.yin.ai.platform.gateway.tenant.policy.TenantFunctionPolicy;
import com.yin.ai.platform.gateway.tenant.policy.TenantFunctionPolicyStore;
import com.yin.ai.platform.gateway.tenant.policy.TenantPolicyProperties;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class TenantFunctionPolicyFilter implements GatewayFilter {

    private final TenantFunctionPolicyStore policyStore;
    private final TenantPolicyProperties properties;
    private final RoadmapFeatureProperties rollout;
    private final Optional<MeterRegistry> meterRegistry;

    public TenantFunctionPolicyFilter(TenantFunctionPolicyStore policyStore,
                                      TenantPolicyProperties properties,
                                      RoadmapFeatureProperties rollout,
                                      Optional<MeterRegistry> meterRegistry) {
        this.policyStore = policyStore;
        this.properties = properties;
        this.rollout = rollout;
        this.meterRegistry = meterRegistry;
    }

    @Override public String name() { return "tenant-function-policy"; }
    @Override public int order() { return 8; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        authorize(request);
        return chain.proceed(request);
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return Flux.defer(() -> {
            authorize(request);
            return chain.proceed(request);
        });
    }

    void authorize(GatewayRequest request) {
        Set<String> requested = requestedFunctions(request);
        if (requested.isEmpty()) {
            return;
        }
        Set<String> allowed = allowedFunctions(request.getTenantId());
        Set<String> denied = new LinkedHashSet<>(requested);
        denied.removeAll(allowed);
        if (denied.isEmpty()) {
            count("allowed");
            request.setFunctionNames(Set.copyOf(requested));
            return;
        }
        boolean enforce = rollout.getTenantIsolation().isEnforcing();
        count(enforce ? "denied" : "shadow_denied");
        if (!enforce) {
            log.warn("[TENANT-FUNCTION] shadow denial tenant={} functions={}",
                    request.getTenantId(), denied);
            return;
        }
        throw new FunctionAccessDeniedException(request.getTenantId(), denied);
    }

    private Set<String> requestedFunctions(GatewayRequest request) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (request.getFunctionNames() != null) {
            requested.addAll(request.getFunctionNames());
        }
        if (request.getOptions() != null && request.getOptions().getForcedToolName() != null) {
            requested.add(request.getOptions().getForcedToolName());
        }
        return requested;
    }

    private Set<String> allowedFunctions(String tenantId) {
        Set<String> tenantAllowed = tenantId == null
                ? properties.getSafeSystemFunctions()
                : policyStore.getPolicy(tenantId)
                .map(TenantFunctionPolicy::allowedFunctions)
                .orElse(properties.getSafeSystemFunctions());
        LinkedHashSet<String> allowed = new LinkedHashSet<>(tenantAllowed);
        if (!properties.getPlatformAllowedFunctions().isEmpty()) {
            allowed.retainAll(properties.getPlatformAllowedFunctions());
        }
        return allowed;
    }

    private void count(String outcome) {
        meterRegistry.ifPresent(registry -> Counter.builder("ai.tenant.function.policy.total")
                .tag("outcome", outcome)
                .register(registry)
                .increment());
    }

    public static class FunctionAccessDeniedException extends RuntimeException {
        public FunctionAccessDeniedException(String tenantId, Set<String> denied) {
            super("Functions are not allowed for tenant " + tenantId + ": " + denied);
        }
    }
}
