package com.yin.ai.platform.gateway.tenant.policy;

import java.util.Optional;

public interface TenantFunctionPolicyStore {

    Optional<TenantFunctionPolicy> getPolicy(String tenantId);

    void savePolicy(TenantFunctionPolicy policy);

    void deletePolicy(String tenantId);
}
