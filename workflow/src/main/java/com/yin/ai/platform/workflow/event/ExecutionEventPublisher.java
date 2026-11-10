package com.yin.ai.platform.workflow.event;

/**
 * 执行事件发布 SPI — SSE 只是观察通道，断开不影响任务执行。
 */
public interface ExecutionEventPublisher {

    /**
     * 发布事件。实现方负责幂等与序列编号。
     */
    void publish(ExecutionEvent event);

    /**
     * 发布携带进度数据的步骤事件。
     */
    default void step(String taskRunId, String conversationId, String traceId,
                      long sequence, ExecutionEventType type, String stepId, Object data) {
        publish(ExecutionEvent.of(taskRunId, conversationId, traceId, sequence, type,
                java.util.Map.of("stepId", stepId == null ? "" : stepId,
                        "data", data == null ? "" : data)));
    }
}
