package com.yin.ai.platform.gateway.tenant;

import com.yin.ai.platform.gateway.tenant.concurrency.InMemoryTenantConcurrencyTracker;
import com.yin.ai.platform.gateway.tenant.concurrency.RedisTenantConcurrencyTracker;
import com.yin.ai.platform.gateway.tenant.concurrency.TenantConcurrencyTracker;
import com.yin.ai.platform.gateway.tenant.policy.InMemoryTenantFunctionPolicyStore;
import com.yin.ai.platform.gateway.tenant.policy.RedisTenantFunctionPolicyStore;
import com.yin.ai.platform.gateway.tenant.policy.TenantFunctionPolicyStore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class TenantQuotaConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantQuotaStore tenantQuotaStore(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson != null) {
            return new RedisTenantQuotaStore(redisson);
        }
        return new InMemoryTenantQuotaStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantFunctionPolicyStore tenantFunctionPolicyStore(
            ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        return redisson == null
                ? new InMemoryTenantFunctionPolicyStore()
                : new RedisTenantFunctionPolicyStore(redisson);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantConcurrencyTracker tenantConcurrencyTracker(
            ObjectProvider<RedissonClient> redissonProvider) {
        InMemoryTenantConcurrencyTracker local = new InMemoryTenantConcurrencyTracker();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        return redisson == null ? local : new RedisTenantConcurrencyTracker(redisson, local);
    }

    static class InMemoryTenantQuotaStore implements TenantQuotaStore {
        private final ConcurrentHashMap<String, TenantQuota> store = new ConcurrentHashMap<>();

        @Override
        public java.util.Optional<TenantQuota> getQuota(String tenantId) {
            return java.util.Optional.ofNullable(store.get(tenantId));
        }

        @Override
        public void saveQuota(TenantQuota quota) {
            store.put(quota.tenantId(), quota);
        }

        @Override
        public void deleteQuota(String tenantId) {
            store.remove(tenantId);
        }

        @Override
        public java.util.List<TenantQuota> listAll() {
            return new java.util.ArrayList<>(store.values());
        }
    }
}
