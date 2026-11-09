package com.yin.ai.platform.core.memory.store;

/**
 * 存储后端类型 — 用于 {@link MemoryStore#storeType()} 标识实现类型。
 * CUSTOM 预留扩展点，第三方可实现自定义存储后端。
 */
public enum StoreType {
    IN_MEMORY,
    REDIS,
    MYSQL,
    MILVUS,
    ELASTICSEARCH,
    CUSTOM
}
