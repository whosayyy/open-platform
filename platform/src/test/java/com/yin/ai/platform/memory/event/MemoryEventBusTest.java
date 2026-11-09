package com.yin.ai.platform.memory.event;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryEventBus 事件总线")
class MemoryEventBusTest {

    private DefaultMemoryEventBus eventBus;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        eventBus = new DefaultMemoryEventBus();
        memoryId = new MemoryId("test-memory", MemoryType.WORKING, "owner");
    }

    @Test
    @DisplayName("发布事件后监听器收到事件")
    void shouldDeliverEventToListener() {
        List<MemoryEvent> received = new ArrayList<>();
        eventBus.register(received::add);

        eventBus.publish(MemoryEventType.CREATED, memoryId, null, "trace-1", Map.of());

        assertEquals(1, received.size());
        assertEquals(MemoryEventType.CREATED, received.get(0).type());
        assertEquals(memoryId, received.get(0).memoryId());
        assertEquals("trace-1", received.get(0).traceId());
    }

    @Test
    @DisplayName("多个监听器都收到事件")
    void shouldDeliverToMultipleListeners() {
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        eventBus.register(e -> count1.incrementAndGet());
        eventBus.register(e -> count2.incrementAndGet());

        eventBus.publish(MemoryEventType.APPENDED, memoryId, null, "t", Map.of());

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    @Test
    @DisplayName("supports 返回 false 时过滤事件")
    void shouldFilterBySupports() {
        AtomicInteger count = new AtomicInteger(0);
        eventBus.register(new MemoryEventListener() {
            @Override
            public void onEvent(MemoryEvent event) {
                count.incrementAndGet();
            }

            @Override
            public boolean supports(MemoryEventType type) {
                return type == MemoryEventType.CREATED;
            }
        });

        eventBus.publish(MemoryEventType.APPENDED, memoryId, null, "t", Map.of());
        assertEquals(0, count.get());

        eventBus.publish(MemoryEventType.CREATED, memoryId, null, "t", Map.of());
        assertEquals(1, count.get());
    }

    @Test
    @DisplayName("监听器异常不影响其他监听器")
    void shouldIsolateListenerExceptions() {
        AtomicInteger goodCount = new AtomicInteger(0);
        eventBus.register(e -> { throw new RuntimeException("broken"); });
        eventBus.register(e -> goodCount.incrementAndGet());

        eventBus.publish(MemoryEventType.CREATED, memoryId, null, "t", Map.of());

        assertEquals(1, goodCount.get());
    }

    @Test
    @DisplayName("注销后不再收到事件")
    void shouldNotReceiveAfterUnregister() {
        AtomicInteger count = new AtomicInteger(0);
        MemoryEventListener listener = e -> count.incrementAndGet();
        eventBus.register(listener);
        eventBus.publish(MemoryEventType.CREATED, memoryId, null, "t", Map.of());
        assertEquals(1, count.get());

        eventBus.unregister(listener);
        eventBus.publish(MemoryEventType.CREATED, memoryId, null, "t", Map.of());
        assertEquals(1, count.get());
    }

    @Test
    @DisplayName("listenerCount 反映实际注册数")
    void shouldReportCorrectListenerCount() {
        assertEquals(0, eventBus.listenerCount());
        eventBus.register(e -> {});
        assertEquals(1, eventBus.listenerCount());
        eventBus.register(e -> {});
        assertEquals(2, eventBus.listenerCount());
    }

    @Test
    @DisplayName("MemoryEvent.of 创建完整事件对象")
    void shouldCreateValidEvent() {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("cell-1"), MemoryType.WORKING, "hello");
        MemoryEvent event = MemoryEvent.of(MemoryEventType.APPENDED, memoryId, cell,
                "trace-1", 42, Map.of("key", "value"));

        assertNotNull(event.eventId());
        assertEquals(MemoryEventType.APPENDED, event.type());
        assertEquals(memoryId, event.memoryId());
        assertEquals(cell, event.cell());
        assertEquals("trace-1", event.traceId());
        assertEquals(42, event.sequenceNumber());
        assertEquals("value", event.data().get("key"));
    }
}
