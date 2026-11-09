package com.yin.ai.platform.core.memory;

/**
 * Memory 类型枚举 — 每种类型有不同的生命周期、存储策略和检索方式。
 * <p>
 * WORKING:  任务进行中的临时状态，Session 级别，主要 L1 堆内存储
 * EPISODIC: 时间线事件/经历，长期追加，Redis Stream / DB Timeseries
 * SEMANTIC: 事实/知识/关系，持久化，Vector Store + Graph DB
 * PROCEDURAL: 可复用的任务完成过程，持久化，带版本管理
 * SESSION:  兼容的聊天历史枚举；新写入归一化为 EPISODIC + SESSION scope，TTL 默认 PT30M
 */
public enum MemoryType {
    WORKING,
    EPISODIC,
    SEMANTIC,
    PROCEDURAL,
    /**
     * @deprecated Session is a scope, not a cognitive memory type. New writes use
     * {@link #EPISODIC} with {@code MemoryScope.SESSION} metadata.
     */
    @Deprecated(since = "0.0.1", forRemoval = false)
    SESSION
}
