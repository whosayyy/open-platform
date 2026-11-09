package com.yin.ai.platform.memory.observe;

import com.yin.ai.platform.memory.event.MemoryEvent;
import com.yin.ai.platform.memory.event.MemoryEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory 链路追踪器 — 监听 {@link MemoryEvent} 并记录操作 Timeline。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>按 traceId 聚合 Memory 操作链路</li>
 *   <li>记录每个 Memory 操作的时序和上下文</li>
 *   <li>为调试和审计提供操作回放数据</li>
 * </ul>
 * <p>
 * 内存占用控制：每个 traceId 最多保留 100 条 TraceRecord，超出丢弃最早记录。
 * 生产环境应接入 OpenTelemetry / Jaeger 等外部追踪系统。
 */
public class MemoryTracer implements MemoryEventListener {

    private static final int MAX_RECORDS_PER_TRACE = 100;

    private final Map<String, List<TraceRecord>> traces = new ConcurrentHashMap<>();

    @Override
    public void onEvent(MemoryEvent event) {
        String traceId = event.traceId() != null ? event.traceId() : "unknown";
        TraceRecord record = new TraceRecord(
                event.type().name(),
                event.memoryId() != null ? event.memoryId().id() : null,
                event.timestamp(),
                event.data());

        traces.compute(traceId, (id, records) -> {
            if (records == null) {
                records = Collections.synchronizedList(new ArrayList<>());
            }
            if (records.size() >= MAX_RECORDS_PER_TRACE) {
                records.remove(0);
            }
            records.add(record);
            return records;
        });
    }

    /** 获取某个 Trace 的完整操作记录。 */
    public List<TraceRecord> getTrace(String traceId) {
        List<TraceRecord> records = traces.get(traceId);
        return records != null ? List.copyOf(records) : List.of();
    }

    /** 获取所有已知 Trace ID。 */
    public List<String> getTraceIds() {
        return List.copyOf(traces.keySet());
    }

    /** Trace 总数。 */
    public int traceCount() {
        return traces.size();
    }

    /** 清空过期 Trace 记录。 */
    public int purgeBefore(Instant cutoff) {
        int purged = 0;
        for (List<TraceRecord> records : traces.values()) {
            synchronized (records) {
                int before = records.size();
                records.removeIf(r -> r.timestamp().isBefore(cutoff));
                purged += (before - records.size());
            }
        }
        return purged;
    }

    /** 清空全部 Trace。 */
    public void clear() {
        traces.clear();
    }

    /**
     * 单条 Trace 记录。
     *
     * @param eventType 事件类型
     * @param memoryId  Memory ID
     * @param timestamp 时间戳
     * @param data      附加数据
     */
    public record TraceRecord(
            String eventType,
            String memoryId,
            Instant timestamp,
            Map<String, Object> data) {
    }
}
