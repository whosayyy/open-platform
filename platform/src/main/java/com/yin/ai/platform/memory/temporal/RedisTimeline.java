package com.yin.ai.platform.memory.temporal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.memory.exception.MemoryStoreException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Timeline 实现 — 基于 Redisson RList 的分布式追加日志。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>RList 是 Redis LIST 结构的封装，RPUSH 操作是原子的，天然线程安全</li>
 *   <li>globalSequence 使用 Redis INCR 生成全局唯一递增序号，跨实例唯一</li>
 *   <li>读操作使用 LRANGE 按偏移范围读取，支持 Cursor 模式</li>
 * </ul>
 * <p>
 * 分布式特征：所有实例共享同一 Redis LIST，追加立即可见，
 * 通过 INCR 序列号保证跨实例全局排序一致。
 */
@Slf4j
public class RedisTimeline implements Timeline {

    private static final String KEY_PREFIX = "ai:memory:timeline:";
    private static final String SEQ_SUFFIX = ":seq";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String streamId;
    private final long ttlSeconds;

    public RedisTimeline(RedissonClient redissonClient, String streamId) {
        this(redissonClient, new ObjectMapper(), streamId, 7200);
    }

    public RedisTimeline(RedissonClient redissonClient, ObjectMapper objectMapper,
                          String streamId, long ttlSeconds) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.streamId = streamId;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public TimelineEntry append(MemoryCell cell, String traceContext) {
        try {
            long seq = redissonClient.getAtomicLong(KEY_PREFIX + streamId + SEQ_SUFFIX)
                    .incrementAndGet();
            TimelinePosition pos = new TimelinePosition(seq, streamId);
            MemoryCell stamped = cell.withSequenceNumber(seq);
            TimelineEntry entry = TimelineEntry.of(seq, pos, stamped, traceContext);

            String json = objectMapper.writeValueAsString(entry);
            RList<String> list = getList();
            list.add(json);
            list.expire(Duration.ofSeconds(ttlSeconds));
            return entry;
        } catch (JsonProcessingException e) {
            throw new MemoryStoreException("Failed to serialize TimelineEntry", e);
        }
    }

    @Override
    public List<TimelineEntry> appendBatch(List<MemoryCell> cells, String traceContext) {
        List<TimelineEntry> result = new ArrayList<>();
        for (MemoryCell cell : cells) {
            result.add(append(cell, traceContext));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<TimelineEntry> readFrom(TimelinePosition start, int limit) {
        RList<String> list = getList();
        int size = list.size();
        if (size == 0) return List.of();

        int fromIndex = findIndexBySequence(list, start.offset());
        if (fromIndex < 0) return List.of();

        int toIndex = Math.min(fromIndex + limit, size) - 1;
        if (fromIndex > toIndex) return List.of();

        return deserializeRange(list, fromIndex, toIndex);
    }

    @Override
    public List<TimelineEntry> readRange(TimelinePosition start, TimelinePosition end) {
        RList<String> list = getList();
        int size = list.size();
        if (size == 0) return List.of();

        int fromIndex = findIndexBySequence(list, start.offset());
        if (fromIndex < 0) return List.of();

        int toIndex = findLastIndexBySequence(list, end.offset(), fromIndex);
        if (toIndex < 0 || fromIndex > toIndex) return List.of();

        return deserializeRange(list, fromIndex, Math.min(toIndex, size - 1));
    }

    @Override
    public Optional<TimelineEntry> lastEntry() {
        RList<String> list = getList();
        int size = list.size();
        if (size == 0) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(list.get(size - 1), TimelineEntry.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize last entry", e);
            return Optional.empty();
        }
    }

    @Override
    public TimelinePosition position() {
        long seq = redissonClient.getAtomicLong(KEY_PREFIX + streamId + SEQ_SUFFIX).get();
        return new TimelinePosition(seq, streamId);
    }

    @Override
    public long size() {
        return getList().size();
    }

    private RList<String> getList() {
        return redissonClient.getList(KEY_PREFIX + streamId, StringCodec.INSTANCE);
    }

    private int findIndexBySequence(RList<String> list, long targetSeq) {
        int size = list.size();
        for (int i = 0; i < size; i++) {
            try {
                TimelineEntry entry = objectMapper.readValue(list.get(i), TimelineEntry.class);
                if (entry.globalSequence() >= targetSeq) {
                    return i;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        return -1;
    }

    private int findLastIndexBySequence(RList<String> list, long targetSeq, int from) {
        int size = list.size();
        int lastMatch = -1;
        for (int i = from; i < size; i++) {
            try {
                TimelineEntry entry = objectMapper.readValue(list.get(i), TimelineEntry.class);
                if (entry.globalSequence() <= targetSeq) {
                    lastMatch = i;
                } else {
                    break;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        return lastMatch;
    }

    private List<TimelineEntry> deserializeRange(RList<String> list, int from, int to) {
        List<TimelineEntry> result = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            try {
                result.add(objectMapper.readValue(list.get(i), TimelineEntry.class));
            } catch (JsonProcessingException e) {
                log.warn("跳过损坏的 TimelineEntry at index {}", i, e);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
