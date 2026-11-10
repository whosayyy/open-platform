package com.yin.ai.platform.gateway.tenant.concurrency;

import java.time.Duration;
import java.util.Optional;

public interface TenantConcurrencyTracker {

    Optional<TenantConcurrencyLease> tryAcquire(String tenantId, int limit, Duration leaseDuration);
}
