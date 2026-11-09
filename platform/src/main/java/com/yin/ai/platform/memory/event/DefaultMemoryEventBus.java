package com.yin.ai.platform.memory.event;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link MemoryEventBus} 的默认实现 — 基于 {@link CopyOnWriteArrayList} 的同步事件总线。
 * <p>
 * 设计理由：
 * <ul>
 *   <li>读远多于写 — CopyOnWriteArrayList 对遍历零锁，适合事件分发场景</li>
 *   <li>同步发布 — 保证事件-状态一致性，避免异步带来的顺序问题</li>
 *   <li>监听器异常隔离 — 单个监听器抛异常不影响后续监听器和事件总线本身</li>
 * </ul>
 */
public class DefaultMemoryEventBus implements MemoryEventBus {

    private final List<MemoryEventListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(MemoryEventType type, MemoryId memoryId, MemoryCell cell,
                         String traceId, Map<String, Object> data) {
        MemoryEvent event = MemoryEvent.of(type, memoryId, cell, traceId, 0, data);
        publish(event);
    }

    @Override
    public void publish(MemoryEvent event) {
        for (MemoryEventListener listener : listeners) {
            if (!listener.supports(event.type())) {
                continue;
            }
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {
                // 单个监听器失败不影响其他监听器
            }
        }
    }

    @Override
    public void register(MemoryEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void unregister(MemoryEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public int listenerCount() {
        return listeners.size();
    }
}
