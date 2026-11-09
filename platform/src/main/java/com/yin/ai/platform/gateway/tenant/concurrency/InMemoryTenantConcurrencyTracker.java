package com.yin.ai.platform.gateway.tenant.concurrency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryTenantConcurrencyTracker implements TenantConcurrencyTracker {

    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantConcurrencyLease> tryAcquire(String tenantId, int limit, Duration leaseDuration) {
        if (limit <= 0) {
            return Optional.of(noopLease(tenantId));
        }
        AtomicInteger counter = active.computeIfAbsent(tenantId, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return Optional.empty();
            }
            if (counter.compareAndSet(current, current + 1)) {
                return Optional.of(countedLease(tenantId, counter));
            }
        }
    }

    int activeCount(String tenantId) {
        AtomicInteger value = active.get(tenantId);
        return value == null ? 0 : value.get();
    }

    private TenantConcurrencyLease countedLease(String tenantId, AtomicInteger counter) {
        String leaseId = UUID.randomUUID().toString();
        AtomicBoolean closed = new AtomicBoolean();
        return new TenantConcurrencyLease() {
            @Override public String tenantId() { return tenantId; }
            @Override public String leaseId() { return leaseId; }
            @Override public void close() {
                if (closed.compareAndSet(false, true)) {
                    counter.updateAndGet(value -> Math.max(0, value - 1));
                }
            }
        };
    }

    private TenantConcurrencyLease noopLease(String tenantId) {
        String leaseId = "unlimited-" + UUID.randomUUID();
        return new TenantConcurrencyLease() {
            @Override public String tenantId() { return tenantId; }
            @Override public String leaseId() { return leaseId; }
            @Override public void close() { }
        };
    }
}
