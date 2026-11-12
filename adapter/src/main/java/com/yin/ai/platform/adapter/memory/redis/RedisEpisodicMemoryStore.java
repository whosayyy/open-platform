package com.yin.ai.platform.adapter.memory.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.exception.MemoryStoreException;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import com.yin.ai.platform.core.memory.MemoryCell;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Episodic 存储适配器 — 基于 Redisson RList + Lua CAS 的高并发分布式实现。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>写入：使用 RList RPUSH 原子追加，单 key 无锁竞争</li>
 *   <li>CAS 更新：使用 Redis Lua 脚本（EVAL）原子性检查版本号 + HSET，
 *       保证多实例并发写同一 Cell 时不会丢更新（乐观锁模式）</li>
 *   <li>序列化：使用 Jackson JSON + StringCodec，与已有 SessionMemoryManager 一致</li>
 *   <li>TTL：默认 2h 无活动过期，可通过构造函数覆盖</li>
 * </ul>
 * <p>
 * 分布式特征：
 * <ul>
 *   <li>多实例共享 Redis 后端，天然一致</li>
 *   <li>CAS Lua 脚本保证写操作的线性一致性</li>
 *   <li>RList 的 RPUSH 本身是原子的，追加无冲突</li>
 * </ul>
 */
@Slf4j
public class RedisEpisodicMemoryStore implements EpisodicMemoryStore {

    private static final String KEY_PREFIX = "ai:memory:episodic:";
    /**
     * CAS 版本哈希（独立 key，避免与时间线 RList 同 key 冲突 WRONGTYPE）。
     * key 形如 ai:memory:episodic:{ownerKey}:{id}:cas
     */
    private static final String CAS_KEY_SUFFIX = ":cas";
    /**
     * CAS 更新脚本（P0-1 修复）：版本校验通过后，不仅写版本哈希，
     * 还原子地更新 timeline RList 中对应 cellId 的条目（LRANGE 全量 → 替换 → DEL + RPUSH + 恢复 TTL），
     * 保证 readAll/readTimeline 读到的是最新内容，Redis 与 CAS 数据不再分叉。
     */
    private static final String CAS_SCRIPT = """
            local casKey = KEYS[1]
            local timelineKey = KEYS[2]
            local cellId = ARGV[1]
            local cellJson = ARGV[2]
            local expectedVersion = tonumber(ARGV[3])
            local ttlMillis = tonumber(ARGV[4])
            local currentVersion = redis.call('HGET', casKey, 'version:' .. cellId)
            if currentVersion == false then
                currentVersion = 0
            else
                currentVersion = tonumber(currentVersion)
            end
            if currentVersion ~= expectedVersion then
                return 0
            end
            redis.call('HSET', casKey, 'cell:' .. cellId, cellJson,
                                       'version:' .. cellId, expectedVersion + 1)
            -- 同步更新 timeline RList：替换同 cellId 条目
            local items = redis.call('LRANGE', timelineKey, 0, -1)
            if #items > 0 then
                local updated = false
                for i, item in ipairs(items) do
                    local ok, cell = pcall(cjson.decode, item)
                    if ok and type(cell) == 'table' and type(cell.cellId) == 'table' and cell.cellId.id == cellId then
                        items[i] = cellJson
                        updated = true
                        break
                    end
                end
                if updated then
                    redis.call('DEL', timelineKey)
                    redis.call('RPUSH', timelineKey, unpack(items, 1, #items))
                    if ttlMillis > 0 then
                        redis.call('PEXPIRE', timelineKey, ttlMillis)
                    end
                end
            end
            return 1
            """;
    /**
     * 原子删除单个 Cell：LRANGE 全量 → 按 cellId.id 过滤 → DEL + RPUSH 保留项 + PEXPIRE。
     * 单脚本内完成，避免读-改-写竞态；cellId 为空时删除整条时间线。
     */
    private static final String DELETE_CELL_SCRIPT = """
            local key = KEYS[1]
            local cellId = ARGV[1]
            local ttlMillis = tonumber(ARGV[2])
            local items = redis.call('LRANGE', key, 0, -1)
            if #items == 0 then
                return 0
            end
            if cellId == '' then
                redis.call('DEL', key)
                return 1
            end
            local retained = {}
            local removed = 0
            for i, item in ipairs(items) do
                local ok, cell = pcall(cjson.decode, item)
                local match = false
                if ok and type(cell) == 'table' and type(cell.cellId) == 'table' and cell.cellId.id == cellId then
                    match = true
                end
                if match then
                    removed = 1
                else
                    table.insert(retained, item)
                end
            end
            if removed == 1 then
                redis.call('DEL', key)
                if #retained > 0 then
                    redis.call('RPUSH', key, unpack(retained, 1, #retained))
                    if ttlMillis > 0 then
                        redis.call('PEXPIRE', key, ttlMillis)
                    end
                end
            end
            return removed
            """;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final AtomicLong localSequence;

    public RedisEpisodicMemoryStore(RedissonClient redissonClient) {
        this(redissonClient, createDefaultObjectMapper(), Duration.ofDays(7));
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    public RedisEpisodicMemoryStore(RedissonClient redissonClient, ObjectMapper objectMapper,
                                     long ttlSeconds) {
        this(redissonClient, objectMapper, Duration.ofSeconds(ttlSeconds));
    }

    public RedisEpisodicMemoryStore(RedissonClient redissonClient, ObjectMapper objectMapper,
                                     Duration ttl) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        this.ttl = ttl != null ? ttl : Duration.ofDays(7);
        this.localSequence = new AtomicLong(0);
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
    public MemoryCell append(MemoryId id, MemoryCell cell) {
        try {
            String json = objectMapper.writeValueAsString(cell);
            RList<String> list = getTimelineList(id);
            list.add(json);
            list.expire(ttl);
            long seq = localSequence.incrementAndGet();
            return cell.withSequenceNumber(seq);
        } catch (JsonProcessingException e) {
            throw new MemoryStoreException("Failed to serialize MemoryCell: " + cell.cellId(), e);
        }
    }

    @Override
    public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells) {
        List<MemoryCell> result = new ArrayList<>();
        for (MemoryCell cell : cells) {
            result.add(append(id, cell));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<MemoryCell> readTimeline(MemoryId id, int limit) {
        RList<String> list = getTimelineList(id);
        int size = list.size();
        if (size == 0) return List.of();

        int from = Math.max(0, size - limit);
        List<String> raw = list.range(from, size - 1);
        return deserializeList(raw);
    }

    @Override
    public List<MemoryCell> readAll(MemoryId id) {
        RList<String> list = getTimelineList(id);
        List<String> raw = list.readAll();
        return deserializeList(raw);
    }

    @Override
    public long size(MemoryId id) {
        return getTimelineList(id).size();
    }

    @Override
    public void refreshRetention(MemoryId id) {
        getTimelineList(id).expire(ttl);
        redissonClient.getBucket(key(id) + CAS_KEY_SUFFIX).expire(ttl);
    }

    @Override
    public void deleteAll(MemoryId id) {
        getTimelineList(id).delete();
        redissonClient.getKeys().delete(key(id) + CAS_KEY_SUFFIX);
    }

    @Override
    public boolean deleteCell(MemoryId id, MemoryCellId cellId) {
        try {
            String key = key(id);
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            Object result = script.eval(RScript.Mode.READ_WRITE,
                    DELETE_CELL_SCRIPT,
                    RScript.ReturnType.INTEGER,
                    List.of(key),
                    cellId == null ? "" : cellId.id(),
                    String.valueOf(ttl.toMillis()));
            boolean removed = result instanceof Long && (Long) result == 1;
            if (removed) {
                // 同步清理 CAS 版本哈希中的对应条目
                try {
                    redissonClient.getMap(key + CAS_KEY_SUFFIX, StringCodec.INSTANCE)
                            .fastRemove("version:" + cellId.id(), "cell:" + cellId.id());
                } catch (Exception e) {
                    log.debug("[MEM-REDIS] CAS hash cleanup failed: {}", e.getMessage());
                }
            }
            return removed;
        } catch (Exception e) {
            log.warn("[MEM-REDIS] deleteCell script failed, fallback to default rewrite: {}", e.getMessage());
            return EpisodicMemoryStore.super.deleteCell(id, cellId);
        }
    }

    @Override
    public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        try {
            String cellJson = objectMapper.writeValueAsString(cell);
            // CAS 版本哈希独立 key，避免与时间线 RList 同 key WRONGTYPE
            String casKey = key(id) + CAS_KEY_SUFFIX;
            String timelineKey = key(id);
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            Object result = script.eval(RScript.Mode.READ_WRITE,
                    CAS_SCRIPT,
                    RScript.ReturnType.INTEGER,
                    List.of(casKey, timelineKey),
                    cell.cellId().id(), cellJson, String.valueOf(expectedVersion),
                    String.valueOf(ttl.toMillis()));
            return result instanceof Long && (Long) result == 1;
        } catch (JsonProcessingException e) {
            throw new MemoryStoreException("Failed to serialize for CAS: " + cell.cellId(), e);
        }
    }

    /**
     * 存储 key：含 ownerKey（tenant）前缀，防止跨用户同 id 碰撞；
     * ownerKey 为空（系统内部记忆）保持原格式。
     */
    private String key(MemoryId id) {
        if (id.ownerKey() == null || id.ownerKey().isBlank()) {
            return KEY_PREFIX + id.id();
        }
        return KEY_PREFIX + id.ownerKey() + ":" + id.id();
    }

    private RList<String> getTimelineList(MemoryId id) {
        return redissonClient.getList(key(id), StringCodec.INSTANCE);
    }

    private List<MemoryCell> deserializeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<MemoryCell> result = new ArrayList<>();
        for (String json : raw) {
            try {
                result.add(objectMapper.readValue(json, MemoryCell.class));
            } catch (JsonProcessingException e) {
                log.warn("跳过损坏的 MemoryCell: {}", json, e);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
