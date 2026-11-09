package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.orchestration.ConsolidationRequest;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;
import com.yin.ai.platform.memory.orchestration.MemoryConsolidationService;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Redis ZSET due queue with one Redisson lock per session. */
public class RedisSessionConsolidationSchedule implements SessionConsolidationSchedule {
    private static final Logger log = LoggerFactory.getLogger(RedisSessionConsolidationSchedule.class);
    private static final String QUEUE_KEY = "ai:memory:consolidation:due";
    private static final String META_PREFIX = "ai:memory:consolidation:context:";
    private static final String LOCK_PREFIX = "ai:memory:consolidation:lock:";
    private static final String OWNER_PREFIX = "ai:session:owner:";
    private final RedissonClient redissonClient;
    private final MemoryConsolidationService service;

    public RedisSessionConsolidationSchedule(RedissonClient redissonClient,
                                             MemoryConsolidationService service) {
        this.redissonClient = redissonClient;
        this.service = service;
    }

    @Override
    public void schedule(String sessionId, Instant dueAt) {
        if (sessionId == null || dueAt == null) return;
        queue().add(dueAt.toEpochMilli(), sessionId);
    }

    public void schedule(String sessionId, Instant dueAt, MemoryAccessContext context) {
        schedule(sessionId, dueAt);
        if (context != null) {
            String value = String.join("\t", safe(context.tenantId()), safe(context.userId()),
                    safe(context.taskId()), safe(context.workflowId()), safe(context.agentId()));
            redissonClient.getBucket(META_PREFIX + sessionId, StringCodec.INSTANCE)
                    .set(value, 8, TimeUnit.DAYS);
        }
    }

    @Override
    public void scheduleNow(String sessionId) {
        schedule(sessionId, Instant.now());
    }

    @Scheduled(fixedDelayString = "${ai.memory.consolidation.poll-interval:PT30S}")
    public void processDue() {
        Collection<String> due = queue().valueRange(0, true,
                Instant.now().toEpochMilli(), true, 0, 50);
        for (String sessionId : due) process(sessionId);
    }

    private void process(String sessionId) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + sessionId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!acquired) return;
            MemoryAccessContext context = context(sessionId);
            var result = service.consolidate(new ConsolidationRequest(
                    MemoryId.of(sessionId, MemoryType.EPISODIC, context.userId()),
                    List.of(), context, false));
            switch (result.status()) {
                case COMPLETED, SKIPPED -> queue().remove(sessionId);
                case RETRY_PENDING, FAILED -> rescheduleWithBackoff(sessionId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            log.warn("[MEMORY] scheduled consolidation failed for {}: {}",
                    sessionId, exception.getMessage());
            rescheduleWithBackoff(sessionId);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void rescheduleWithBackoff(String sessionId) {
        String retryKey = META_PREFIX + "retry:" + sessionId;
        long retry = redissonClient.getAtomicLong(retryKey).incrementAndGet();
        redissonClient.getAtomicLong(retryKey).expire(8, TimeUnit.DAYS);
        if (retry <= 3) {
            schedule(sessionId, Instant.now().plusSeconds(30L * (1L << (retry - 1))));
        } else {
            queue().remove(sessionId);
            log.error("[MEMORY] consolidation exhausted three retries for {}", sessionId);
        }
    }

    private MemoryAccessContext context(String sessionId) {
        Object raw = redissonClient.getBucket(META_PREFIX + sessionId, StringCodec.INSTANCE).get();
        String[] values = raw != null ? raw.toString().split("\\t", -1) : new String[0];
        String userId = value(values, 1);
        if (userId == null) {
            Object owner = redissonClient.getBucket(OWNER_PREFIX + sessionId, StringCodec.INSTANCE).get();
            userId = owner != null ? owner.toString() : null;
        }
        String tenantId = value(values, 0);
        if (tenantId == null) tenantId = userId;
        return new MemoryAccessContext(tenantId, userId, sessionId, value(values, 2),
                value(values, 3), value(values, 4), null, Set.of(), Set.of(), null);
    }

    private RScoredSortedSet<String> queue() {
        return redissonClient.getScoredSortedSet(QUEUE_KEY, StringCodec.INSTANCE);
    }

    private String value(String[] values, int index) {
        return index < values.length && !values[index].isBlank() ? values[index] : null;
    }

    private String safe(String value) { return value != null ? value : ""; }
}
