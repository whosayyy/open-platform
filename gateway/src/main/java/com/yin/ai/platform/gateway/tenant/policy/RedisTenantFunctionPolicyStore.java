package com.yin.ai.platform.gateway.tenant.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RedisTenantFunctionPolicyStore implements TenantFunctionPolicyStore {

    private static final String REDIS_KEY = "ai:tenant:function-policies";

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ConcurrentHashMap<String, TenantFunctionPolicy> lastGood = new ConcurrentHashMap<>();

    public RedisTenantFunctionPolicyStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Optional<TenantFunctionPolicy> getPolicy(String tenantId) {
        try {
            Object raw = map().get(tenantId);
            if (raw == null) {
                return Optional.ofNullable(lastGood.get(tenantId));
            }
            TenantFunctionPolicy policy = objectMapper.readValue(raw.toString(), TenantFunctionPolicy.class);
            lastGood.put(tenantId, policy);
            return Optional.of(policy);
        } catch (Exception error) {
            log.warn("[TENANT-FUNCTION] Redis unavailable, using last-good policy: tenant={} error={}",
                    tenantId, error.getMessage());
            return Optional.ofNullable(lastGood.get(tenantId));
        }
    }

    @Override
    public void savePolicy(TenantFunctionPolicy policy) {
        try {
            map().put(policy.tenantId(), objectMapper.writeValueAsString(policy));
            lastGood.put(policy.tenantId(), policy);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to save tenant function policy", error);
        }
    }

    @Override
    public void deletePolicy(String tenantId) {
        map().remove(tenantId);
        lastGood.remove(tenantId);
    }

    private RMap<Object, Object> map() {
        return redisson.getMap(REDIS_KEY);
    }
}
