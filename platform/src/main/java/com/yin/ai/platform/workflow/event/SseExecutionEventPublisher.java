package com.yin.ai.platform.workflow.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.engine.stream.StreamPacketHistoryWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 执行事件发布器 — 将执行事件转换为前端可渲染的 StreamPacket。
 *
 * <p>事件经 SinkStreamProducer 推送到 SSE；SSE 断开不终止任务，
 * 用户重连后由 Sink 回放缓冲继续读取。
 */
@Slf4j
@Component
public class SseExecutionEventPublisher implements ExecutionEventPublisher {

    private final SinkStreamProducer sinkStreamProducer;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StreamPacketHistoryWriter> historyWriterProvider;

    public SseExecutionEventPublisher(SinkStreamProducer sinkStreamProducer,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<StreamPacketHistoryWriter> historyWriterProvider) {
        this.sinkStreamProducer = sinkStreamProducer;
        this.objectMapper = objectMapper;
        this.historyWriterProvider = historyWriterProvider;
    }

    @Override
    public void publish(ExecutionEvent event) {
        try {
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("eventType", event.type().name());
            legacy.put("stepId", event.payload() != null ? event.payload().get("stepId") : null);
            legacy.put("data", event.payload() != null ? event.payload().get("data") : null);
            StreamPacket packet = StreamPacket.builder()
                    .type("thinking")
                    .content(objectMapper.writeValueAsString(legacy))
                    .eventId(event.eventId())
                    .eventType(protocolName(event.type()))
                    .sessionId(event.conversationId())
                    .stepId(event.payload() != null ? stringValue(event.payload().get("stepId")) : null)
                    .traceId(event.traceId())
                    .payload(event.payload() == null ? Map.of() : event.payload())
                    .build();
            publishPacket(event.taskRunId(), packet);

            if (event.type() == ExecutionEventType.ARTIFACT_DELTA) {
                Object data = event.payload() != null ? event.payload().get("data") : null;
                if (data != null && !String.valueOf(data).isEmpty()) {
                    // 产物增量只进 sink/history（供右侧 Artifact 面板成稿），不累积到
                    // textAccumulator——complete 事件不再携带全文，对话流只展示总结。
                    publishPacket(event.taskRunId(), StreamPacket.builder()
                            .type("text")
                            .content(String.valueOf(data))
                            .eventId(event.eventId())
                            .eventType("artifact.delta")
                            .sessionId(event.conversationId())
                            .traceId(event.traceId())
                            .payload(event.payload())
                            .build(), false);
                }
            } else if (event.type() == ExecutionEventType.TASK_COMPLETED) {
                complete(event.taskRunId());
            } else if (event.type() == ExecutionEventType.TASK_FAILED
                    || event.type() == ExecutionEventType.TASK_CANCELLED) {
                complete(event.taskRunId());
            }
        } catch (Exception e) {
            log.debug("[WF-EVENT] publish failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * 向 SSE 发射最终文本（任务完成时的文章全文）。
     */
    public void emitText(String taskRunId, String text) {
        try {
            sinkStreamProducer.emitText(taskRunId, text);
        } catch (Exception e) {
            log.debug("[WF-EVENT] emitText failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * 完成信号（前端据此结束流）。
     */
    public void complete(String taskRunId) {
        try {
            StreamPacket complete = StreamPacket.builder()
                    .type("complete")
                    .content(sinkStreamProducer.getAccumulatedText(taskRunId))
                    .eventType("stream.completed")
                    .payload(Map.of())
                    .build();
            publishPacket(taskRunId, complete);
            sinkStreamProducer.complete(taskRunId);
        } catch (Exception e) {
            log.debug("[WF-EVENT] complete failed (non-fatal): {}", e.getMessage());
        }
    }

    private void publishPacket(String taskRunId, StreamPacket packet) {
        publishPacket(taskRunId, packet, true);
    }

    /**
     * 发布包到 sink/history。
     *
     * @param accumulate 是否累积到 textAccumulator（false 用于产物增量：避免 complete 携带全文）
     */
    private void publishPacket(String taskRunId, StreamPacket packet, boolean accumulate) {
        StreamPacketHistoryWriter writer = historyWriterProvider.getIfAvailable();
        if (writer != null) {
            writer.publish(taskRunId, packet);
        }
        if (accumulate) {
            sinkStreamProducer.emitPacket(taskRunId, packet);
        } else {
            sinkStreamProducer.emitArtifactDelta(taskRunId,
                    packet.getContent() == null ? "" : String.valueOf(packet.getContent()));
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String protocolName(ExecutionEventType type) {
        return switch (type) {
            case TASK_CREATED -> "task.created";
            case TASK_STARTED -> "task.started";
            case PLAN_CREATED -> "plan.created";
            case STEP_READY -> "step.created";
            case STEP_STARTED -> "step.started";
            case STEP_PROGRESS -> "step.progress";
            case STEP_RETRYING -> "step.retrying";
            case STEP_SUMMARY -> "step.summary";
            case STEP_COMPLETED -> "step.completed";
            case STEP_FAILED -> "step.failed";
            case TOOL_STARTED -> "tool.started";
            case TOOL_COMPLETED -> "tool.completed";
            case TOOL_FAILED -> "tool.failed";
            case ARTIFACT_CREATED -> "artifact.versioned";
            case ARTIFACT_STARTED -> "artifact.started";
            case ARTIFACT_DELTA -> "artifact.delta";
            case ARTIFACT_COMPLETED -> "artifact.completed";
            case TASK_WAITING_INPUT -> "task.paused";
            case TASK_SUMMARY -> "task.summary";
            case TASK_COMPLETED -> "task.completed";
            case TASK_FAILED -> "task.failed";
            case TASK_CANCELLED -> "task.cancelled";
        };
    }
}
