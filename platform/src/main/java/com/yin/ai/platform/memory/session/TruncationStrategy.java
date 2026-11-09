package com.yin.ai.platform.memory.session;

import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.List;

/**
 * Truncation 策略 SPI — 按 Token 预算截断 MemoryCell 列表。
 * <p>
 * 从 {@link com.yin.ai.platform.memory.SlidingWindowTruncator} 提取的通用接口，
 * 不同实现可以有不同的截断粒度：
 * <ul>
 *   <li>简单截断 — 按 Cell 粒度假定丢弃最早的消息</li>
 *   <li>轮次感知截断 — 以对话轮次（user + assistant 对）为最小截断单位</li>
 *   <li>重要性感知截断 — 按 relevance score 保留高优先级 Cell</li>
 * </ul>
 */
@FunctionalInterface
public interface TruncationStrategy {

    /**
     * 按 Token 预算截断 Cell 列表。
     *
     * @param cells     原始 Cell 列表（按时间排序）
     * @param maxTokens Token 预算上限
     * @return 截断后的 Cell 列表（保持原顺序）
     */
    List<MemoryCell> truncate(List<MemoryCell> cells, int maxTokens);
}
