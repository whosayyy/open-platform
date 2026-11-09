package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.List;
import java.util.Optional;

/**
 * Timeline 接口 — 追加日志，不可变写入。
 * <p>
 * 设计理由：Timeline 是 Episodic Memory 的时间维度抽象。
 * Redis 后端实现为 Stream，MySQL 后端实现为自增表，InMemory 后端实现为 ArrayList。
 * 所有实现必须保证 append 是原子的且 globalSequence 单调递增。
 */
public interface Timeline {

    /** 追加一个 Cell 到 Timeline，返回带位置的条目。 */
    TimelineEntry append(MemoryCell cell, String traceContext);

    /** 批量追加。 */
    List<TimelineEntry> appendBatch(List<MemoryCell> cells, String traceContext);

    /** 从指定位置读取最多 limit 条。 */
    List<TimelineEntry> readFrom(TimelinePosition start, int limit);

    /** 读取指定范围内的所有条目。 */
    List<TimelineEntry> readRange(TimelinePosition start, TimelinePosition end);

    /** 获取最后一个条目（用于恢复时确定回放起点）。 */
    Optional<TimelineEntry> lastEntry();

    /** 当前 Timeline 位置。 */
    TimelinePosition position();

    /** Timeline 中的条目总数。 */
    long size();
}
