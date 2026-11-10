package com.yin.ai.platform.gateway.tenant.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTenantConcurrencyTrackerTest {

    @Test
    void leaseLimitIsAtomicAndCloseIsIdempotent() {
        InMemoryTenantConcurrencyTracker tracker = new InMemoryTenantConcurrencyTracker();
        TenantConcurrencyLease lease = tracker.tryAcquire("t1", 1, Duration.ofMinutes(1)).orElseThrow();
        assertThat(tracker.activeCount("t1")).isOne();
        assertThat(tracker.tryAcquire("t1", 1, Duration.ofMinutes(1))).isEmpty();
        lease.close();
        lease.close();
        assertThat(tracker.activeCount("t1")).isZero();
        assertThat(tracker.tryAcquire("t1", 1, Duration.ofMinutes(1))).isPresent();
    }
}
