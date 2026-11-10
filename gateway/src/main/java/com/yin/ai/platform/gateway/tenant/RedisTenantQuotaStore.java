package com.yin.ai.platform.gateway.tenant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class RedisTenantQuotaStore implements TenantQuotaStore {

    private static final String REDIS_KEY = "ai:tenant:quotas";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final RedissonClient redisson;

    public RedisTenantQuotaStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Optional<TenantQuota> getQuota(String tenantId) {
        Object raw = redisson.getMap(REDIS_KEY).get(tenantId);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(raw.toString(), TenantQuota.class));
        } catch (JsonProcessingException e) {
            log.warn("[TENANT-QUOTA] deserialize failed for {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveQuota(TenantQuota quota) {
        try {
            String json = MAPPER.writeValueAsString(quota);
            redisson.getMap(REDIS_KEY).put(quota.tenantId(), json);
            log.info("[TENANT-QUOTA] saved quota for tenant={}", quota.tenantId());
        } catch (JsonProcessingException e) {
            log.error("[TENANT-QUOTA] save failed for {}: {}", quota.tenantId(), e.getMessage());
        }
    }

    @Override
    public void deleteQuota(String tenantId) {
        redisson.getMap(REDIS_KEY).remove(tenantId);
        log.info("[TENANT-QUOTA] deleted quota for tenant={}", tenantId);
    }

    @Override
    public List<TenantQuota> listAll() {
        List<TenantQuota> result = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : redisson.getMap(REDIS_KEY).entrySet()) {
            try {
                result.add(MAPPER.readValue(entry.getValue().toString(), TenantQuota.class));
            } catch (Exception ignored) { }
        }
        return result;
    }
}
