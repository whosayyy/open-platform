package com.yin.ai.platform.gateway.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.Set;

/**
 * Redis RMap 语义缓存存储实现。
 * <p>
 * 所有实例共享同一个 Redis Hash，保证多实例数据一致性。
 * 余弦相似度计算在 {@link SemanticCacheManager} 中完成。
 */
@Slf4j
public class RedisSemanticCacheStore implements SemanticCacheStore {

    private static final String REDIS_KEY = "ai:cache:semantic:entries";

    private final RMap<String, String> cacheMap;

    public RedisSemanticCacheStore(RedissonClient redisson) {
        this.cacheMap = redisson.getMap(REDIS_KEY, StringCodec.INSTANCE);
        log.info("[SEMANTIC-STORE] Redis RMap initialized: key={}", REDIS_KEY);
    }

    @Override
    public Map<String, String> readAll() {
        return cacheMap.readAllMap();
    }

    @Override
    public void put(String key, String jsonValue) {
        cacheMap.fastPut(key, jsonValue);
    }

    @Override
    public void remove(String key) {
        cacheMap.fastRemove(key);
    }

    @Override
    public Set<String> keys() {
        return cacheMap.keySet();
    }

    @Override
    public int size() {
        return cacheMap.size();
    }
}
