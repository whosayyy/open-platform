package com.yin.ai.platform.memory.policy;

/**
 * 压缩触发条件枚举。
 */
public enum CompressionTrigger {
    /** Cell 数量超过阈值触发 */
    SIZE,
    /** 时间跨度超过阈值触发 */
    TIME,
    /** 仅手动触发 */
    MANUAL
}
