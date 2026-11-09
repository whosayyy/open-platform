package com.yin.ai.platform.core.memory.store;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

/**
 * MemoryStore 根 SPI — 所有存储后端的共同基类。
 * <p>
 * 设计理由：不定义一个"全能"接口，而是按 MemoryType 拆分为
 * {@link WorkingMemoryStore}、EpisodicMemoryStore 等子接口。
 * 后端可按需实现，例如 Redis 只实现 Working + Episodic，MySQL 只实现 Semantic + Procedural。
 * <p>
 * 每个实现通过 {@link #supports(MemoryType)} 声明能力，
 * 由 {@link com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime} 动态路由。
 */
public interface MemoryStore {

    StoreType storeType();

    boolean supports(MemoryType memoryType);

    StoreHealth health();

    /**
     * CAS 乐观锁写入 — 仅当当前版本号等于 expectedVersion 时才写入，并自增版本号。
     * 返回 true 表示写入成功，false 表示版本冲突（调用方应重试：re-read → re-apply → CAS write）。
     * 默认实现无锁写入（不检查版本），子类覆盖以支持分布式并发控制。
     */
    default boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        return false;
    }
}
