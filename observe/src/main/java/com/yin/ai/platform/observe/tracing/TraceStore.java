package com.yin.ai.platform.observe.tracing;

import java.util.List;

/**
 * Trace 存储接口 — 支持内存和 Elasticsearch 两种实现。
 */
public interface TraceStore {

    /** 存储一个 Span。 */
    void store(TraceSpan span);

    /** 按 traceId 查询所有 Span。 */
    List<TraceSpan> getTraceSpans(String traceId);

    /** 获取最近的 Trace 摘要列表。 */
    List<TraceSummary> getRecentTraces(int limit);

    /** 按时间范围查询 Trace 摘要列表（startTime/endTime 为 epoch millis）。 */
    default List<TraceSummary> getTracesByTimeRange(long startTime, long endTime, int limit) {
        return getRecentTraces(limit);
    }

    /** 当前存储的 Trace 数（近似值）。 */
    int size();

    /** 清空所有数据。 */
    void clear();

    /** Span 数量统计（用于 stats）。 */
    default long totalSpanCount() { return -1; }
}
