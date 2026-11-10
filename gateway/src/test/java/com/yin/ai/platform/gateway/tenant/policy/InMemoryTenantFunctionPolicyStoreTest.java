package com.yin.ai.platform.gateway.tenant.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTenantFunctionPolicyStoreTest {

    @Test
    void stalePolicyVersionCannotReplaceLastGoodPolicy() {
        InMemoryTenantFunctionPolicyStore store = new InMemoryTenantFunctionPolicyStore();
        store.savePolicy(new TenantFunctionPolicy("t1", Set.of("searchNews"), 2, Instant.now()));
        store.savePolicy(new TenantFunctionPolicy("t1", Set.of("dangerous"), 1, Instant.now()));
        assertThat(store.getPolicy("t1").orElseThrow().allowedFunctions())
                .containsExactly("searchNews");
        store.deletePolicy("t1");
        assertThat(store.getPolicy("t1")).isEmpty();
    }
}
