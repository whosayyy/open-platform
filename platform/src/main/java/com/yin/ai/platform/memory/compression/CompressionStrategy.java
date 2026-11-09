package com.yin.ai.platform.memory.compression;

/**
 * 压缩策略枚举。
 */
public enum CompressionStrategy {
    /** LLM 摘要压缩 — 保留语义，损失细节 */
    SUMMARIZE,
    /** Token 预算截断 — 丢弃超出预算的最早内容 */
    TRUNCATE,
    /** 关键信息提取 — 只保留高重要性 Cell */
    EXTRACT,
    /** 自定义策略 */
    CUSTOM
}
