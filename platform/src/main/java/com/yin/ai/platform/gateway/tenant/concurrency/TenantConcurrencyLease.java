package com.yin.ai.platform.gateway.tenant.concurrency;

public interface TenantConcurrencyLease extends AutoCloseable {

    String tenantId();

    String leaseId();

    @Override
    void close();
}
