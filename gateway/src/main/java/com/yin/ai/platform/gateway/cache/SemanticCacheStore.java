package com.yin.ai.platform.gateway.cache;

import java.util.Map;
import java.util.Set;

/**
 * 语义缓存存储 SPI — 抽象底层存储引擎，支持多实例数据一致性。
 * <p>
 * 当前实现：{@link RedisSemanticCacheStore} — Redis RMap + Java cosine 相似度。
 * 未来实现：Elasticsearch dense_vector kNN 搜索（替换 cosine 为 ES 原生向量检索）。
 */
public interface SemanticCacheStore {

    /** 读取所有缓存条目（key → JSON）。 */
    Map<String, String> readAll();

    /** 写入一条缓存条目。 */
    void put(String key, String jsonValue);

    /** 删除一条缓存条目。 */
    void remove(String key);

    /** 获取所有 key（用于驱逐遍历）。 */
    Set<String> keys();

    /** 近似条目数。 */
    int size();
}
