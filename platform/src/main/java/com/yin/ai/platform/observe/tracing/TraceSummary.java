package com.yin.ai.platform.observe.tracing;

import java.util.List;

/**
 * Trace 摘要信息，用于管理端列表展示。
 */
public class TraceSummary {

    private String traceId;
    private int spanCount;
    private long startTime;
    private long durationMs;
    private String status;
    private String rootSpanName;

    public TraceSummary() {
    }

    public TraceSummary(String traceId, int spanCount, long startTime,
                        long durationMs, String status, String rootSpanName) {
        this.traceId = traceId;
        this.spanCount = spanCount;
        this.startTime = startTime;
        this.durationMs = durationMs;
        this.status = status;
        this.rootSpanName = rootSpanName;
    }

    public static TraceSummary fromSpans(String traceId, List<TraceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new TraceSummary(traceId, 0, 0, 0, "EMPTY", null);
        }
        TraceSpan first = spans.get(0);
        long startTime = spans.stream().mapToLong(TraceSpan::getStartTime).min().orElse(0);
        long endTime = spans.stream().mapToLong(TraceSpan::getEndTime).max().orElse(0);
        long durationMs = (endTime - startTime) / 1_000_000;
        boolean hasError = spans.stream().anyMatch(s -> "ERROR".equals(s.getStatus()));
        return new TraceSummary(
                traceId,
                spans.size(),
                startTime,
                durationMs,
                hasError ? "ERROR" : "OK",
                first.getName()
        );
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public int getSpanCount() { return spanCount; }
    public void setSpanCount(int spanCount) { this.spanCount = spanCount; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRootSpanName() { return rootSpanName; }
    public void setRootSpanName(String rootSpanName) { this.rootSpanName = rootSpanName; }
}
