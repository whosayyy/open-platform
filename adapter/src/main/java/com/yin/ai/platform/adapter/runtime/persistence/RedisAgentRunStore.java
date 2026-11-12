package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.core.agent.store.AgentRunRecord;
import com.yin.ai.platform.core.agent.store.AgentRunStore;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Redis infrastructure adapter for the framework-neutral AgentRunStore SPI. */
public final class RedisAgentRunStore implements AgentRunStore {

    public static final String DEFAULT_KEY_PREFIX = "ai:core:agent:";

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final AgentStateJsonCodec codec = new AgentStateJsonCodec();

    public RedisAgentRunStore(RedissonClient redisson) {
        this(redisson, DEFAULT_KEY_PREFIX);
    }

    public RedisAgentRunStore(RedissonClient redisson, String keyPrefix) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
    }

    @Override
    public boolean create(AgentRunRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.revision() != 0L) {
            throw new IllegalArgumentException("new records must start at revision zero");
        }
        RLock lock = lock(record.taskId());
        lock.lock();
        try {
            RBucket<String> bucket = bucket(record.taskId());
            if (bucket.get() != null) return false;
            bucket.set(codec.encode(record.toPortableMap()));
            try {
                index(record);
            } catch (RuntimeException exception) {
                bucket.delete();
                throw exception;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean update(AgentRunRecord record) {
        Objects.requireNonNull(record, "record");
        RLock lock = lock(record.taskId());
        lock.lock();
        try {
            RBucket<String> bucket = bucket(record.taskId());
            String stored = bucket.get();
            if (stored == null) return false;
            AgentRunRecord current = decode(stored);
            if (record.revision() != current.revision() + 1L
                    || !Objects.equals(record.parentTaskId(), current.parentTaskId())) {
                return false;
            }
            bucket.set(codec.encode(record.toPortableMap()));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<AgentRunRecord> load(String taskId) {
        if (taskId == null || taskId.isBlank()) return Optional.empty();
        return Optional.ofNullable(bucket(taskId).get()).map(this::decode);
    }

    @Override
    public List<AgentRunRecord> listByParent(String parentTaskId) {
        if (parentTaskId == null || parentTaskId.isBlank()) return List.of();
        String normalized = parentTaskId.trim();
        return children(normalized).readAll().stream()
                .map(this::load)
                .flatMap(Optional::stream)
                .filter(record -> normalized.equals(record.parentTaskId()))
                .sorted(Comparator.comparing(AgentRunRecord::createdAt)
                        .thenComparing(AgentRunRecord::taskId))
                .toList();
    }

    private AgentRunRecord decode(String value) {
        return AgentRunRecord.fromPortableMap(codec.decode(value));
    }

    private void index(AgentRunRecord record) {
        if (record.parentTaskId() != null) {
            children(record.parentTaskId()).add(record.taskId());
        }
    }

    private RBucket<String> bucket(String taskId) {
        return redisson.getBucket(keyPrefix + "run:v1:" + taskId, StringCodec.INSTANCE);
    }

    private RSet<String> children(String parentTaskId) {
        return redisson.getSet(keyPrefix + "children:v1:" + parentTaskId,
                StringCodec.INSTANCE);
    }

    private RLock lock(String taskId) {
        return redisson.getLock(keyPrefix + "run-lock:v1:" + taskId);
    }
}
