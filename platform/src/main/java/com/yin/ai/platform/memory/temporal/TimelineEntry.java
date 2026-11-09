package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryCell;

import java.time.Instant;

/**
 * Timeline 条目 — MemoryCell 在 Timeline 中的位置记录。
 * <p>
 * 不可变：Timeline 是追加日志，条目一旦写入不可修改。
 * globalSequence 是全局单调递增序号，用于全序排序和断点续传。
 *
 * @param globalSequence 全局单调递增序号
 * @param position       在 Timeline 中的精确位置
 * @param cell           关联的 MemoryCell
 * @param recordedAt     写入 Timeline 的时间
 * @param traceContext   链路追踪 ID（对应 traceId）
 */
public record TimelineEntry(
        long globalSequence,
        TimelinePosition position,
        MemoryCell cell,
        Instant recordedAt,
        String traceContext) {

    public static TimelineEntry of(long sequence, TimelinePosition position,
                                    MemoryCell cell, String traceContext) {
        return new TimelineEntry(sequence, position, cell, Instant.now(), traceContext);
    }
}
