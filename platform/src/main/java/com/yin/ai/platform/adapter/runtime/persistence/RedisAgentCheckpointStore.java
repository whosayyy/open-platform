package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Objects;
import java.util.Optional;

/** Redis infrastructure adapter for resumable core pattern checkpoints. */
public final class RedisAgentCheckpointStore implements AgentCheckpointStore {

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final AgentStateJsonCodec codec = new AgentStateJsonCodec();

    public RedisAgentCheckpointStore(RedissonClient redisson) {
        this(redisson, RedisAgentRunStore.DEFAULT_KEY_PREFIX);
    }

    public RedisAgentCheckpointStore(RedissonClient redisson, String keyPrefix) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
    }

    @Override
    public Optional<AgentCheckpoint> load(String taskId) {
        if (taskId == null || taskId.isBlank()) return Optional.empty();
        return Optional.ofNullable(bucket(taskId).get())
                .map(codec::decode)
                .map(AgentCheckpoint::fromPortableMap);
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        RLock lock = lock(checkpoint.taskId());
        lock.lock();
        try {
            RBucket<String> bucket = bucket(checkpoint.taskId());
            String stored = bucket.get();
            if (stored != null) {
                AgentCheckpoint current = AgentCheckpoint.fromPortableMap(codec.decode(stored));
                if (current.revision() > checkpoint.revision()) return;
            }
            bucket.set(codec.encode(checkpoint.toPortableMap()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String taskId) {
        if (taskId != null && !taskId.isBlank()) bucket(taskId).delete();
    }

    private RBucket<String> bucket(String taskId) {
        return redisson.getBucket(keyPrefix + "checkpoint:v1:" + taskId,
                StringCodec.INSTANCE);
    }

    private RLock lock(String taskId) {
        return redisson.getLock(keyPrefix + "checkpoint-lock:v1:" + taskId);
    }
}
