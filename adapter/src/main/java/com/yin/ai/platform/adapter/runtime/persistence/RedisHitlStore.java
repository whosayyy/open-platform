package com.yin.ai.platform.adapter.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yin.ai.platform.agent.hitl.HitlCheckpoint;
import com.yin.ai.platform.agent.hitl.HitlStore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Redis adapter for the legacy human-in-the-loop checkpoint store port. */
public class RedisHitlStore implements HitlStore {

    private static final Logger log = LoggerFactory.getLogger(RedisHitlStore.class);
    private static final String KEY_PREFIX = "ai:hitl:pending:";

    private final RedissonClient redisson;
    private final ObjectMapper mapper;

    public RedisHitlStore(RedissonClient redisson) {
        this.redisson = redisson;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void save(HitlCheckpoint checkpoint) {
        try {
            String json = mapper.writeValueAsString(checkpoint);
            redisson.getBucket(KEY_PREFIX + checkpoint.taskId()).set(json, Duration.ofMinutes(5));
        } catch (JsonProcessingException e) {
            log.error("[HITL-REDIS] serialize failed taskId={}: {}", checkpoint.taskId(), e.getMessage());
        }
    }

    @Override
    public Optional<HitlCheckpoint> getPendingByTask(String taskId) {
        String json = (String) redisson.getBucket(KEY_PREFIX + taskId).get();
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, HitlCheckpoint.class));
        } catch (JsonProcessingException e) {
            log.warn("[HITL-REDIS] deserialize failed taskId={}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<HitlCheckpoint> getPending() {
        List<HitlCheckpoint> result = new ArrayList<>();
        for (String key : redisson.getKeys().getKeysByPattern(KEY_PREFIX + "*", 100)) {
            String json = (String) redisson.getBucket(key).get();
            if (json != null) {
                try {
                    result.add(mapper.readValue(json, HitlCheckpoint.class));
                } catch (JsonProcessingException ignored) {
                    // Ignore corrupt entries; callers still receive every valid checkpoint.
                }
            }
        }
        result.removeIf(HitlCheckpoint::isExpired);
        return result;
    }

    @Override
    public void remove(String taskId) {
        redisson.getBucket(KEY_PREFIX + taskId).delete();
    }

    @Override
    public int pendingCount() {
        int count = 0;
        for (String ignored : redisson.getKeys().getKeysByPattern(KEY_PREFIX + "*", 100)) {
            count++;
        }
        return count;
    }
}
