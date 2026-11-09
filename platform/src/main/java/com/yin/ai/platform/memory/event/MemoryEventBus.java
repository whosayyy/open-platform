package com.yin.ai.platform.memory.event;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;

import java.util.Map;

/**
 * Memory 事件总线 — 同步发布-订阅模型。
 * <p>
 * 设计理由：Memory 事件是低延迟、同进程的观察通知，不需要异步/持久化（有 Timeline 做持久事件记录）。
 * 同步发布确保事件-状态一致性：事件发布完成后，状态转换已对所有监听器可见。
 * 如果需要异步，监听器内部自行提交到线程池。
 */
public interface MemoryEventBus {

    /** 发布事件（便捷方法，自动填充 MemoryEvent）。 */
    void publish(MemoryEventType type, MemoryId memoryId, MemoryCell cell,
                 String traceId, Map<String, Object> data);

    /** 发布预构建的事件。 */
    void publish(MemoryEvent event);

    /** 注册监听器。 */
    void register(MemoryEventListener listener);

    /** 注销监听器。 */
    void unregister(MemoryEventListener listener);

    /** 已注册监听器数量（用于测试断言）。 */
    int listenerCount();
}
