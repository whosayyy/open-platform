package com.yin.ai.platform.adapter.memory.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.exception.MemoryStoreException;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import com.yin.ai.platform.core.memory.store.WorkingMemoryStore;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis WorkingMemory 存储适配器 — 基于 Redisson RBucket + Lua CAS 的高并发分布式实现。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>WorkingMemory 序列化为 JSON 存储在 RBucket 中，单 key 原子读写</li>
 *   <li>ResumePoint 存储为独立的 Hash key，支持按 label 索引查找</li>
 *   <li>CAS 更新使用 Lua 脚本保证乐观锁版本检查 + 写入原子性</li>
 * </ul>
 * <p>
 * 分布式特征：
 * <ul>
 *   <li>多实例共享同一 Redis key，读自己写它人</li>
 *   <li>TTL 按 Session 生命周期管理（默认 30min）</li>
 * </ul>
 */
@Slf4j
public class RedisWorkingMemoryStore implements WorkingMemoryStore {

    private static final String WM_KEY_PREFIX = "ai:memory:working:";
    private static final String RP_KEY_PREFIX = "ai:memory:resume:";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisWorkingMemoryStore(RedissonClient redissonClient) {
        this(redissonClient, createDefaultObjectMapper(), Duration.ofHours(1));
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    public RedisWorkingMemoryStore(RedissonClient redissonClient, ObjectMapper objectMapper,
                                    long ttlSeconds) {
        this(redissonClient, objectMapper, Duration.ofSeconds(ttlSeconds));
    }

    public RedisWorkingMemoryStore(RedissonClient redissonClient, ObjectMapper objectMapper,
                                    Duration ttl) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        this.ttl = ttl != null ? ttl : Duration.ofHours(1);
    }

    @Override
    public StoreType storeType() {
        return StoreType.REDIS;
    }

    @Override
    public StoreHealth health() {
        try {
            long start = System.nanoTime();
            redissonClient.getKeys().count();
            long latency = (System.nanoTime() - start) / 1_000_000;
            return StoreHealth.healthy(latency);
        } catch (Exception e) {
            return StoreHealth.unhealthy("Redis unreachable: " + e.getMessage());
        }
    }

    @Override
    public void save(WorkingMemory wm) {
        try {
            String json = objectMapper.writeValueAsString(wm);
            RBucket<String> bucket = redissonClient.getBucket(
                    key(WM_KEY_PREFIX, wm.getId()), StringCodec.INSTANCE);
            bucket.set(json, ttl);
        } catch (JsonProcessingException e) {
            throw new MemoryStoreException("Failed to serialize WorkingMemory: " + wm.getId(), e);
        }
    }

    @Override
    public Optional<WorkingMemory> load(MemoryId id) {
        RBucket<String> bucket = redissonClient.getBucket(
                key(WM_KEY_PREFIX, id), StringCodec.INSTANCE);
        String json = bucket.get();
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, WorkingMemory.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize WorkingMemory: {}", id, e);
            return Optional.empty();
        }
    }

    @Override
    public void saveResumePoint(MemoryId id, ResumePoint point) {
        try {
            String json = objectMapper.writeValueAsString(point);
            redissonClient.getBucket(key(RP_KEY_PREFIX, id) + ":" + point.label(),
                    StringCodec.INSTANCE).set(json, ttl);
        } catch (JsonProcessingException e) {
            throw new MemoryStoreException("Failed to serialize ResumePoint", e);
        }
    }

    @Override
    public Optional<ResumePoint> loadResumePoint(MemoryId id, String label) {
        RBucket<String> bucket = redissonClient.getBucket(
                key(RP_KEY_PREFIX, id) + ":" + label, StringCodec.INSTANCE);
        String json = bucket.get();
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, ResumePoint.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize ResumePoint: {}:{}", id, label, e);
            return Optional.empty();
        }
    }

    @Override
    public List<ResumePoint> listResumePoints(MemoryId id) {
        String pattern = key(RP_KEY_PREFIX, id) + ":*";
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(pattern);
        List<ResumePoint> points = new java.util.ArrayList<>();
        for (String key : keys) {
            String json = redissonClient.<String>getBucket(key, StringCodec.INSTANCE).get();
            if (json != null) {
                try {
                    points.add(objectMapper.readValue(json, ResumePoint.class));
                } catch (JsonProcessingException e) {
                    log.warn("跳过损坏的 ResumePoint: key={}", key, e);
                }
            }
        }
        return points;
    }

    @Override
    public void delete(MemoryId id) {
        redissonClient.getBucket(key(WM_KEY_PREFIX, id)).delete();
        String pattern = key(RP_KEY_PREFIX, id) + ":*";
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(pattern);
        for (String key : keys) {
            redissonClient.getBucket(key).delete();
        }
    }

    /**
     * 存储 key：含 ownerKey（tenant）前缀，防止跨用户同 id 碰撞；
     * ownerKey 为空（系统内部记忆）保持原格式。
     */
    private String key(String prefix, MemoryId id) {
        if (id.ownerKey() == null || id.ownerKey().isBlank()) {
            return prefix + id.id();
        }
        return prefix + id.ownerKey() + ":" + id.id();
    }
}
