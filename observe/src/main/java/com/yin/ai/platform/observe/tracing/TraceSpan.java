package com.yin.ai.platform.observe.tracing;

import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简化的 Span 数据 DTO，从 OpenTelemetry SpanData 转换而来，
 * 不含 OTEL SDK 依赖，方便序列化输出给管理端 API。
 */
public class TraceSpan {

    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String name;
    private String kind;
    private String status;
    private long startTime;
    private long endTime;
    private long durationMs;
    private Map<String, String> attributes;
    private List<Map<String, Object>> events;

    public TraceSpan() {
    }

    public static TraceSpan fromSpanData(SpanData data) {
        TraceSpan span = new TraceSpan();
        span.traceId = data.getTraceId();
        span.spanId = data.getSpanId();
        span.parentSpanId = data.getParentSpanId();
        span.name = data.getName();
        span.kind = data.getKind().name();
        span.status = data.getStatus().getStatusCode().name();
        span.startTime = data.getStartEpochNanos();
        span.endTime = data.getEndEpochNanos();
        span.durationMs = (data.getEndEpochNanos() - data.getStartEpochNanos()) / 1_000_000;
        Map<String, String> attrs = new LinkedHashMap<>();
        data.getAttributes().forEach((key, value) -> attrs.put(key.getKey(), String.valueOf(value)));
        span.attributes = attrs;

        List<Map<String, Object>> eventList = new ArrayList<>();
        data.getEvents().forEach(event -> {
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("name", event.getName());
            evt.put("timestamp", event.getEpochNanos());
            event.getAttributes().forEach((key, value) -> evt.put(key.getKey(), String.valueOf(value)));
            eventList.add(evt);
        });
        span.events = eventList;
        return span;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }

    public List<Map<String, Object>> getEvents() { return events; }
    public void setEvents(List<Map<String, Object>> events) { this.events = events; }
}
