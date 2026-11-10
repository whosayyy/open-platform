package com.yin.ai.platform.gateway.tenant.concurrency;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class RedisTenantConcurrencyTracker implements TenantConcurrencyTracker {

    private static final String ACQUIRE_SCRIPT = """
            redis.call('zremrangebyscore', KEYS[1], '-inf', ARGV[1])
            local active = redis.call('zcard', KEYS[1])
            if active >= tonumber(ARGV[4]) then return 0 end
            redis.call('zadd', KEYS[1], ARGV[2], ARGV[3])
            redis.call('pexpire', KEYS[1], ARGV[5])
            return 1
            """;

    private final RedissonClient redisson;
    private final TenantConcurrencyTracker fallback;

    public RedisTenantConcurrencyTracker(RedissonClient redisson, TenantConcurrencyTracker fallback) {
        this.redisson = redisson;
        this.fallback = fallback;
    }

    @Override
    public Optional<TenantConcurrencyLease> tryAcquire(String tenantId, int limit, Duration leaseDuration) {
        if (limit <= 0) {
            return fallback.tryAcquire(tenantId, limit, leaseDuration);
        }
        String leaseId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long leaseMillis = Math.max(1_000L, leaseDuration.toMillis());
        String key = "ai:tenant:" + tenantId + ":concurrency";
        try {
            Number acquired = redisson.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    ACQUIRE_SCRIPT,
                    RScript.ReturnType.INTEGER,
                    List.of(key), now, now + leaseMillis, leaseId, limit, leaseMillis + 60_000L);
            if (acquired == null || acquired.longValue() != 1L) {
                return Optional.empty();
            }
            AtomicBoolean closed = new AtomicBoolean();
            return Optional.of(new TenantConcurrencyLease() {
                @Override public String tenantId() { return tenantId; }
                @Override public String leaseId() { return leaseId; }
                @Override public void close() {
                    if (closed.compareAndSet(false, true)) {
                        try {
                            redisson.getScoredSortedSet(key).remove(leaseId);
                        } catch (Exception error) {
                            log.warn("[TENANT-CONCURRENCY] lease release deferred to TTL: tenant={} lease={}",
                                    tenantId, leaseId);
                        }
                    }
                }
            });
        } catch (Exception error) {
            log.warn("[TENANT-CONCURRENCY] Redis unavailable, using node-local limit: tenant={} error={}",
                    tenantId, error.getMessage());
            return fallback.tryAcquire(tenantId, limit, leaseDuration);
        }
    }
}
