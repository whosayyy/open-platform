package com.yin.ai.platform.memory.event;

/**
 * Memory 事件监听器 SPI — 接收 {@link MemoryEventBus} 发布的事件。
 * <p>
 * 设计为函数式接口，Lambda 友好。通过 {@link #supports} 过滤感兴趣的事件类型，
 * 减少不必要的事件分发开销。
 * <p>
 * 典型实现：ObservabilityEventListener（指标上报）、
 * ConsolidationEventListener（自动触发 STM→LTM 转移）、
 * CheckpointEventListener（重大操作后自动快照）。
 */
@FunctionalInterface
public interface MemoryEventListener {

    /** 处理事件。实现应快速返回，避免阻塞事件总线。 */
    void onEvent(MemoryEvent event);

    /** 是否关心此事件类型。默认关心所有类型，子类可覆盖以过滤。 */
    default boolean supports(MemoryEventType type) {
        return true;
    }
}
