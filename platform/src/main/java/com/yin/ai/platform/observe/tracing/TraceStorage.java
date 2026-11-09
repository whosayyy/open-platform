package com.yin.ai.platform.observe.tracing;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * 内存 Trace 存储 — 按 traceId 索引 Span，支持最近 Trace 列表查询。
 * <p>
 * 容量上限 {@code maxTraces}，超出时淘汰最旧的 Trace。
 */
public class TraceStorage implements TraceStore {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TraceSpan>> spansByTraceId;
    private final ConcurrentLinkedDeque<String> traceOrder;
    private final int maxTraces;

    public TraceStorage(int maxTraces) {
        this.maxTraces = maxTraces;
        this.spansByTraceId = new ConcurrentHashMap<>();
        this.traceOrder = new ConcurrentLinkedDeque<>();
    }

    private final LongAdder spanCounter = new LongAdder();

    /** 存储一个 Span。线程安全。 */
    @Override
    public void store(TraceSpan span) {
        if (span == null || span.getTraceId() == null) {
            return;
        }
        spansByTraceId.computeIfAbsent(span.getTraceId(), k -> {
            traceOrder.addFirst(k);
            evictIfNeeded();
            return new CopyOnWriteArrayList<>();
        }).add(span);
        spanCounter.increment();
    }

    @Override
    public List<TraceSpan> getTraceSpans(String traceId) {
        List<TraceSpan> spans = spansByTraceId.get(traceId);
        return spans != null ? Collections.unmodifiableList(spans) : List.of();
    }

    @Override
    public List<TraceSummary> getRecentTraces(int limit) {
        return traceOrder.stream()
                .limit(limit)
                .map(traceId -> {
                    List<TraceSpan> spans = spansByTraceId.get(traceId);
                    if (spans == null || spans.isEmpty()) return null;
                    return TraceSummary.fromSpans(traceId, spans);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public int size() {
        return traceOrder.size();
    }

    @Override
    public long totalSpanCount() { return spanCounter.sum(); }

    /** 清空所有数据。 */
    @Override
    public void clear() {
        spansByTraceId.clear();
        traceOrder.clear();
    }

    private void evictIfNeeded() {
        while (traceOrder.size() > maxTraces) {
            String oldest = traceOrder.pollLast();
            if (oldest != null) {
                spansByTraceId.remove(oldest);
            }
        }
    }
}
