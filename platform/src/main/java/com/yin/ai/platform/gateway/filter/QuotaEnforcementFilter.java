package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import com.yin.ai.platform.gateway.tenant.TenantQuota;
import com.yin.ai.platform.gateway.tenant.TenantQuotaStore;
import com.yin.ai.platform.gateway.tenant.concurrency.TenantConcurrencyLease;
import com.yin.ai.platform.gateway.tenant.concurrency.TenantConcurrencyTracker;
import com.yin.ai.platform.gateway.tenant.policy.TenantPolicyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class QuotaEnforcementFilter implements GatewayFilter {

    private final TenantQuotaStore quotaStore;
    private final TenantConcurrencyTracker concurrencyTracker;
    private final TenantPolicyProperties properties;

    public QuotaEnforcementFilter(TenantQuotaStore quotaStore,
                                  TenantConcurrencyTracker concurrencyTracker,
                                  TenantPolicyProperties properties) {
        this.quotaStore = quotaStore;
        this.concurrencyTracker = concurrencyTracker;
        this.properties = properties;
    }

    @Override public String name() { return "quota-enforcement"; }
    @Override public int order() { return 12; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        Optional<TenantConcurrencyLease> acquired = acquire(request);
        if (acquired == null) {
            return buildQuotaExceededResponse(request);
        }
        if (acquired.isEmpty()) {
            return chain.proceed(request);
        }
        try (TenantConcurrencyLease ignored = acquired.get()) {
            return chain.proceed(request);
        }
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return Flux.defer(() -> {
            Optional<TenantConcurrencyLease> acquired = acquire(request);
            if (acquired == null) {
                return Flux.error(new QuotaExceededException(request.getTenantId(), "concurrentTasks"));
            }
            if (acquired.isEmpty()) {
                return chain.proceed(request);
            }
            TenantConcurrencyLease lease = acquired.get();
            return chain.proceed(request).doFinally(signal -> lease.close());
        });
    }

    /** Empty means no configured limit; null means the configured limit was exceeded. */
    private Optional<TenantConcurrencyLease> acquire(GatewayRequest request) {
        String tenantId = request.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        TenantQuota quota = quotaStore.getQuota(tenantId).orElse(null);
        if (quota == null || quota.maxConcurrentTasks() <= 0) {
            return Optional.empty();
        }
        Optional<TenantConcurrencyLease> lease = concurrencyTracker.tryAcquire(
                tenantId, quota.maxConcurrentTasks(), properties.getConcurrencyLease());
        if (lease.isEmpty()) {
            log.warn("[QUOTA] concurrentTasks exceeded for tenant={}", tenantId);
            return null;
        }
        return lease;
    }

    private GatewayResponse buildQuotaExceededResponse(GatewayRequest request) {
        GatewayResponse response = new GatewayResponse("Tenant concurrent task quota exceeded");
        response.setTraceId(request.getTraceId());
        response.setMetadata(Map.of("quotaExceeded", true, "dimension", "concurrentTasks"));
        return response;
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String tenantId, String dimension) {
            super("Quota exceeded: tenant=" + tenantId + ", dimension=" + dimension);
        }
    }
}
