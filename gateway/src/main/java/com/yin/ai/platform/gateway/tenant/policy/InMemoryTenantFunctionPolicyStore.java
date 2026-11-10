package com.yin.ai.platform.gateway.tenant.policy;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTenantFunctionPolicyStore implements TenantFunctionPolicyStore {

    private final ConcurrentHashMap<String, TenantFunctionPolicy> policies = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantFunctionPolicy> getPolicy(String tenantId) {
        return Optional.ofNullable(policies.get(tenantId));
    }

    @Override
    public void savePolicy(TenantFunctionPolicy policy) {
        policies.compute(policy.tenantId(), (tenantId, current) -> {
            if (current != null && current.version() > policy.version()) {
                return current;
            }
            return policy;
        });
    }

    @Override
    public void deletePolicy(String tenantId) {
        policies.remove(tenantId);
    }
}
