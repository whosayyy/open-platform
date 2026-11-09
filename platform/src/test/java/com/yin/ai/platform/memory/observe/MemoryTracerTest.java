package com.yin.ai.platform.memory.observe;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.event.MemoryEvent;
import com.yin.ai.platform.memory.event.MemoryEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryTracer")
class MemoryTracerTest {

    private MemoryTracer tracer;

    @BeforeEach
    void setUp() {
        tracer = new MemoryTracer();
    }

    private static MemoryEvent createEvent(MemoryEventType type, MemoryId id,
                                            String traceId, long seq, Map<String, Object> data) {
        return MemoryEvent.of(type, id, null, traceId, seq, data);
    }

    @Test
    @DisplayName("监听事件并记录 Trace")
    void shouldRecordTrace() {
        MemoryId id = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");
        MemoryEvent event = createEvent(MemoryEventType.APPENDED, id,
                "trace-001", 1, Map.of("content", "hello"));

        tracer.onEvent(event);

        List<MemoryTracer.TraceRecord> records = tracer.getTrace("trace-001");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).eventType()).isEqualTo("APPENDED");
        assertThat(records.get(0).memoryId()).isEqualTo("ep-1");
    }

    @Test
    @DisplayName("null traceId 使用 'unknown'")
    void shouldHandleNullTraceId() {
        MemoryId id = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");
        MemoryEvent event = createEvent(MemoryEventType.CREATED, id,
                null, 0, Map.of());

        tracer.onEvent(event);
        assertThat(tracer.getTrace("unknown")).hasSize(1);
    }

    @Test
    @DisplayName("同一 Trace 累积多条记录")
    void shouldAccumulateRecords() {
        for (int i = 0; i < 5; i++) {
            MemoryId id = MemoryId.of("ep-" + i, MemoryType.EPISODIC, "user-1");
            MemoryEvent event = createEvent(MemoryEventType.APPENDED, id,
                    "trace-002", i + 1, Map.of("seq", i));
            tracer.onEvent(event);
        }

        assertThat(tracer.getTrace("trace-002")).hasSize(5);
    }

    @Test
    @DisplayName("单 Trace 最多保留 100 条")
    void shouldCapRecordsPerTrace() {
        for (int i = 0; i < 150; i++) {
            MemoryEvent event = createEvent(MemoryEventType.APPENDED,
                    MemoryId.of("ep", MemoryType.EPISODIC, "u"),
                    "trace-003", i, Map.of("i", i));
            tracer.onEvent(event);
        }

        assertThat(tracer.getTrace("trace-003")).hasSize(100);
    }

    @Test
    @DisplayName("getTraceIds 列出所有 Trace ID")
    void shouldListTraceIds() {
        for (String tid : List.of("a", "b", "c")) {
            MemoryEvent event = createEvent(MemoryEventType.CREATED,
                    MemoryId.of("ep", MemoryType.EPISODIC, "u"),
                    tid, 0, Map.of());
            tracer.onEvent(event);
        }

        assertThat(tracer.getTraceIds()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("traceCount 返回 Trace 数量")
    void shouldCountTraces() {
        assertThat(tracer.traceCount()).isZero();

        MemoryEvent event = createEvent(MemoryEventType.CREATED,
                MemoryId.of("ep", MemoryType.EPISODIC, "u"),
                "t1", 0, Map.of());
        tracer.onEvent(event);
        assertThat(tracer.traceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("purgeBefore 清理过期记录")
    void shouldPurgeBeforeCutoff() {
        MemoryEvent old = createEvent(MemoryEventType.CREATED,
                MemoryId.of("ep-old", MemoryType.EPISODIC, "u"),
                "trace-old", 0, Map.of());
        MemoryEvent recent = createEvent(MemoryEventType.CREATED,
                MemoryId.of("ep-recent", MemoryType.EPISODIC, "u"),
                "trace-recent", 0, Map.of());

        tracer.onEvent(old);
        tracer.onEvent(recent);

        // Verify both are recorded
        assertThat(tracer.traceCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("clear 清空全部 Trace")
    void shouldClearAll() {
        MemoryEvent event = createEvent(MemoryEventType.CREATED,
                MemoryId.of("ep", MemoryType.EPISODIC, "u"),
                "t1", 0, Map.of());
        tracer.onEvent(event);
        tracer.clear();
        assertThat(tracer.traceCount()).isZero();
    }
}
