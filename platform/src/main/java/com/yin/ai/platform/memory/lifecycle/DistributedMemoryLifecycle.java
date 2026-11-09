package com.yin.ai.platform.memory.lifecycle;

import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.event.MemoryEventBus;
import com.yin.ai.platform.memory.event.MemoryEventType;
import com.yin.ai.platform.memory.exception.MemoryConcurrencyException;
import com.yin.ai.platform.memory.exception.MemoryNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 分布式 MemoryLifecycle — 基于 Redisson RLock + Redis RBucket 的跨实例状态协调。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>分布式锁（RLock）：每个 transition 操作先获取 MemoryId 粒度的分布式锁，
 *       保证同一 Memory 跨实例的状态转换串行化，避免脑裂</li>
 *   <li>Redis RBucket 状态持久化：LifecycleState 写入 Redis，
 *       其他实例可直接读取最新状态，无需本地缓存</li>
 *   <li>锁超时：默认 5s 自动释放，防止实例宕机导致死锁</li>
 *   <li>锁粒度：每个 MemoryId 独立锁 key，不同 Memory 之间零竞争</li>
 * </ul>
 * <p>
 * 分布式特征：替代 {@link MemoryLifecycle} 的本地 ConcurrentHashMap 存储，
 * 使用 Redis 作为权威状态源，支持多实例并发操作同一个 Memory。
 */
@Slf4j
public class DistributedMemoryLifecycle extends MemoryLifecycle {

    private static final String STATE_KEY_PREFIX = "ai:memory:state:";
    private static final String LOCK_KEY_PREFIX = "ai:memory:lock:";

    private final RedissonClient redissonClient;
    private final long lockTimeoutSeconds;

    public DistributedMemoryLifecycle(MemoryEventBus eventBus, RedissonClient redissonClient) {
        super(eventBus);
        this.redissonClient = redissonClient;
        this.lockTimeoutSeconds = 5;
    }

    public DistributedMemoryLifecycle(MemoryEventBus eventBus, RedissonClient redissonClient,
                                       long lockTimeoutSeconds) {
        super(eventBus);
        this.redissonClient = redissonClient;
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    @Override
    public MemoryId register(MemoryId id) {
        RBucket<String> bucket = redissonClient.getBucket(
                STATE_KEY_PREFIX + id.id(), StringCodec.INSTANCE);
        bucket.set(LifecycleState.CREATED.name());
        super.register(id);
        return id;
    }

    @Override
    public LifecycleState getState(MemoryId id) {
        RBucket<String> bucket = redissonClient.getBucket(
                STATE_KEY_PREFIX + id.id(), StringCodec.INSTANCE);
        String stateName = bucket.get();
        if (stateName != null) {
            return LifecycleState.valueOf(stateName);
        }
        return super.getState(id);
    }

    @Override
    public boolean transition(MemoryId id, LifecycleState target) {
        String lockKey = LOCK_KEY_PREFIX + id.id();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取分布式锁（等待 2s，持有最长 lockTimeoutSeconds 秒）
            if (!lock.tryLock(2, lockTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Failed to acquire lock for transition: {} → {}", id, target);
                throw new MemoryConcurrencyException(
                        "Failed to acquire distributed lock for " + id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemoryConcurrencyException("Lock acquisition interrupted for " + id);
        }

        try {
            // 双重检查：锁内重新读取 Redis 中的最新状态
            LifecycleState current = getState(id);

            if (!current.isTransitionAllowed(target)) {
                return false;
            }

            boolean success = super.transition(id, target);

            if (success) {
                // 写入 Redis 权威状态
                RBucket<String> bucket = redissonClient.getBucket(
                        STATE_KEY_PREFIX + id.id(), StringCodec.INSTANCE);
                bucket.set(target.name());
            }

            return success;
        } finally {
            try {
                lock.unlock();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void forceTransition(MemoryId id, LifecycleState target) {
        String lockKey = LOCK_KEY_PREFIX + id.id();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            lock.lock(lockTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Force transition without lock: {} → {}", id, target);
        }

        try {
            super.forceTransition(id, target);
            RBucket<String> bucket = redissonClient.getBucket(
                    STATE_KEY_PREFIX + id.id(), StringCodec.INSTANCE);
            bucket.set(target.name());
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void unregister(MemoryId id) {
        super.unregister(id);
        redissonClient.getBucket(STATE_KEY_PREFIX + id.id()).delete();
    }
}
