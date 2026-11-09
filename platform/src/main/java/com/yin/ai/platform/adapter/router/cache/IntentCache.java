package com.yin.ai.platform.adapter.router.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.IntentRouteCache;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Intent 路由结果缓存 — L1 Caffeine + L2 Redis 两级缓存。
 * <p>
 * L1 (Caffeine): 本地内存，微秒级延迟，单实例命中。
 * L2 (Redis): 分布式共享，毫秒级延迟，跨实例命中。
 * <p>
 * 缓存的是标准化 Query → IntentMatchResult 的映射，不是最终 AI 回答。
 * 缓存命中时跳过整个 Matcher Chain，直接返回已匹配的意图结果。
 * <p>
 * <b>设计决策：</b>只缓存成功匹配（非 DefaultMatcher 结果），
 * 避免缓存无意义的 CHAT 兜底。
 */
@Slf4j
public class IntentCache implements IntentRouteCache {

    private static final String REDIS_KEY_PREFIX = "ai:router:cache:";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Cache<String, IntentMatchResult> l1Cache;
    private final RedissonClient redisson;
    private final boolean enabled;
    private final int ttlSeconds;

    public IntentCache(IntentCacheOptions options) {
        this(options, null);
    }

    public IntentCache(IntentCacheOptions options, RedissonClient redisson) {
        this.enabled = options.enabled();
        this.ttlSeconds = options.ttlSeconds();
        this.redisson = redisson;
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(options.maxSize())
                .expireAfterWrite(Duration.ofSeconds(options.ttlSeconds()))
                .recordStats()
                .build();
        log.info("[CACHE] enabled={} l1MaxSize={} l2Redis={} ttlSeconds={}",
                enabled, options.maxSize(), redisson != null, ttlSeconds);
    }

    /**
     * 查询缓存：L1 → L2 → miss。
     */
    public Optional<IntentMatchResult> get(String normalizedQuery) {
        if (!enabled || normalizedQuery == null || normalizedQuery.isEmpty()) {
            return Optional.empty();
        }

        // L1: Caffeine
        IntentMatchResult result = l1Cache.getIfPresent(normalizedQuery);
        if (result != null) {
            log.debug("[CACHE] L1 HIT query=\"{}\" intent={}",
                    truncate(normalizedQuery, 60), result.getIntent());
            return Optional.of(result);
        }

        // L2: Redis
        if (redisson != null) {
            result = getFromRedis(normalizedQuery);
            if (result != null) {
                l1Cache.put(normalizedQuery, result);
                log.debug("[CACHE] L2 HIT query=\"{}\" intent={}",
                        truncate(normalizedQuery, 60), result.getIntent());
                return Optional.of(result);
            }
        }

        return Optional.empty();
    }

    /**
     * 写入缓存：双写 L1 + L2。
     */
    public void put(String normalizedQuery, IntentMatchResult result) {
        if (!enabled || normalizedQuery == null || normalizedQuery.isEmpty()) {
            return;
        }
        if ("default".equals(result.getMatchedBy())) {
            return;
        }

        l1Cache.put(normalizedQuery, result);

        if (redisson != null) {
            putToRedis(normalizedQuery, result);
        }

        log.debug("[CACHE] PUT query=\"{}\" intent={} matchedBy={}",
                truncate(normalizedQuery, 60), result.getIntent(), result.getMatchedBy());
    }

    private IntentMatchResult getFromRedis(String query) {
        try {
            RBucket<String> bucket = redisson.getBucket(REDIS_KEY_PREFIX + query);
            String json = bucket.get();
            if (json != null) {
                return objectMapper.readValue(json, IntentMatchResult.class);
            }
        } catch (JsonProcessingException e) {
            log.debug("[CACHE] L2 skip corrupt entry: {}", query);
        } catch (Exception e) {
            log.warn("[CACHE] L2 read failed: {}", e.getMessage());
        }
        return null;
    }

    private void putToRedis(String query, IntentMatchResult result) {
        try {
            RBucket<String> bucket = redisson.getBucket(REDIS_KEY_PREFIX + query);
            bucket.set(objectMapper.writeValueAsString(result), ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] L2 write serialization failed: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[CACHE] L2 write failed: {}", e.getMessage());
        }
    }

    /** 缓存命中次数。 */
    public long hitCount() {
        return l1Cache.stats().hitCount();
    }

    /** 缓存未命中次数。 */
    public long missCount() {
        return l1Cache.stats().missCount();
    }

    /** 预计内存占用。 */
    public long estimatedSize() {
        return l1Cache.estimatedSize();
    }

    /** 清空缓存。 */
    public void invalidateAll() {
        l1Cache.invalidateAll();
        log.info("[CACHE] L1 invalidated");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
