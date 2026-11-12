package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.Artifact;
import com.yin.ai.platform.agent.pattern.ContextSnapshot;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.task.ContextStore;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/** Redis adapter for the legacy Agent Runtime context store port. */
public class RedisContextStore implements ContextStore {

    private static final Logger log = LoggerFactory.getLogger(RedisContextStore.class);
    private static final String KEY_PREFIX = "ai:task:";
    private static final String CONTEXT_SCHEMA = "ctx:v2:";
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(48);

    private final RedissonClient redisson;
    private final ExecutorService asyncExecutor;

    public RedisContextStore(RedissonClient redisson, ExecutorService asyncExecutor) {
        this.redisson = redisson;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public void saveVariableAsync(String taskId, String key, Object value) {
        asyncExecutor.submit(() -> {
            try {
                String redisKey = KEY_PREFIX + taskId + ":" + CONTEXT_SCHEMA + "var:" + key;
                RBucket<Object> bucket = redisson.getBucket(redisKey, LegacyAgentStateJsonCodec.INSTANCE);
                bucket.set(value, SNAPSHOT_TTL);
            } catch (Exception e) {
                log.warn("[CTX-STORE] saveVariable failed taskId={} key={}: {}",
                        taskId, key, e.getMessage());
            }
        });
    }

    @Override
    public void savePlanAsync(String taskId, TaskPlan plan) {
        asyncExecutor.submit(() -> {
            try {
                String redisKey = KEY_PREFIX + taskId + ":" + CONTEXT_SCHEMA + "plan";
                RBucket<TaskPlan> bucket = redisson.getBucket(redisKey, LegacyAgentStateJsonCodec.INSTANCE);
                bucket.set(plan, SNAPSHOT_TTL);
            } catch (Exception e) {
                log.warn("[CTX-STORE] savePlan failed taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    @Override
    public void saveArtifactAsync(String taskId, Artifact artifact) {
        asyncExecutor.submit(() -> {
            try {
                String redisKey = KEY_PREFIX + taskId + ":" + CONTEXT_SCHEMA + "artifacts";
                redisson.getList(redisKey, LegacyAgentStateJsonCodec.INSTANCE).add(artifact);
                redisson.getList(redisKey, LegacyAgentStateJsonCodec.INSTANCE).expire(SNAPSHOT_TTL);
            } catch (Exception e) {
                log.warn("[CTX-STORE] saveArtifact failed taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    @Override
    public void saveSnapshot(String taskId, ContextSnapshot snapshot) {
        try {
            String redisKey = KEY_PREFIX + taskId + ":" + CONTEXT_SCHEMA + "snapshot";
            RBucket<ContextSnapshot> bucket = redisson.getBucket(redisKey, LegacyAgentStateJsonCodec.INSTANCE);
            bucket.set(snapshot, SNAPSHOT_TTL);
            log.debug("[CTX-STORE] snapshot saved taskId={} state={}", taskId, snapshot.state());
        } catch (Exception e) {
            log.error("[CTX-STORE] saveSnapshot failed taskId={}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public AgentExecutionContext restore(String taskId) {
        try {
            ContextSnapshot snapshot = loadSnapshot(taskId);
            if (snapshot == null) {
                log.warn("[CTX-STORE] no snapshot found for taskId={}", taskId);
                return null;
            }
            log.info("[CTX-STORE] snapshot loaded taskId={} state={} step={}",
                    taskId, snapshot.state(), snapshot.stepIndex());
            return null;
        } catch (Exception e) {
            log.error("[CTX-STORE] restore failed taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    @Override
    public ContextSnapshot loadSnapshot(String taskId) {
        String v2Key = KEY_PREFIX + taskId + ":" + CONTEXT_SCHEMA + "snapshot";
        try {
            ContextSnapshot snapshot = redisson.<ContextSnapshot>getBucket(
                    v2Key, LegacyAgentStateJsonCodec.INSTANCE).get();
            if (snapshot != null) return snapshot;
        } catch (RuntimeException e) {
            log.warn("[CTX-STORE] v2 snapshot read failed taskId={}: {}", taskId, e.getMessage());
        }

        String legacyKey = KEY_PREFIX + taskId + ":ctx:snapshot";
        try {
            ContextSnapshot legacy = redisson.<ContextSnapshot>getBucket(legacyKey).get();
            if (legacy != null) saveSnapshot(taskId, legacy);
            return legacy;
        } catch (RuntimeException e) {
            log.error("[CTX-STORE] legacy snapshot read failed taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    @Override
    public void cleanup(String taskId) {
        asyncExecutor.submit(() -> {
            try {
                redisson.getKeys().deleteByPattern(KEY_PREFIX + taskId + ":ctx:*");
                log.info("[CTX-STORE] cleanup done taskId={}", taskId);
            } catch (Exception e) {
                log.warn("[CTX-STORE] cleanup failed taskId={}: {}", taskId, e.getMessage());
            }
        });
    }
}
