package com.yin.ai.platform.memory.policy;

/**
 * 淘汰策略枚举。
 */
public enum EvictionStrategy {
    /** 最近最少使用 — 淘汰最久未被访问的 Cell */
    LRU,
    /** 最不经常使用 — 淘汰访问次数最少的 Cell */
    LFU,
    /** 先进先出 — 淘汰最早创建的 Cell */
    FIFO,
    /** 优先级 — 按自定义权重排序淘汰 */
    PRIORITY
}
