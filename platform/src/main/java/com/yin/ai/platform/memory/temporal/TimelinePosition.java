package com.yin.ai.platform.memory.temporal;

/**
 * Timeline 游标位置 — 标记 Timeline 中的某个偏移点。
 * <p>
 * 设计为不可变 record，支持与 {@link Checkpoint} 关联以记录快照位置，
 * 支持与 {@link ReplaySession} 配合用于从指定位置回放。
 *
 * @param offset   全局偏移量（单调递增）
 * @param streamId 流标识（同一 MemoryId 的多个 Timeline 实例区分）
 */
public record TimelinePosition(long offset, String streamId) {

    public static TimelinePosition origin(String streamId) {
        return new TimelinePosition(0, streamId);
    }

    public boolean isAfter(TimelinePosition other) {
        return this.offset > other.offset;
    }

    public boolean isBefore(TimelinePosition other) {
        return this.offset < other.offset;
    }
}
