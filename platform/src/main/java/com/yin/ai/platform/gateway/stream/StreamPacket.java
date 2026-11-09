package com.yin.ai.platform.gateway.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 统一流式消息包，前端 SSE 消费的基础协议单元。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamPacket {
    /** 消息类型：reasoning | thinking | text | tool_call | complete | error | reference */
    private String type;
    /** 内容：String（thinking/text/error）或 ToolCallPayload（tool_call） */
    private Object content;
    private String taskId;
    private long seq;
    private long ts;
    /** Canonical aliases retained alongside seq/ts during the compatibility window. */
    private long sequence;
    private String timestamp;
    /** Additive Task Event Protocol fields. Legacy clients may keep using type/content. */
    private String eventId;
    private String eventType;
    private String sessionId;
    private String stepId;
    private Map<String, Object> payload;
    /** V2 新增：链路追踪 ID */
    private String traceId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallPayload {
        /** 阶段：invoking | result */
        private String stage;
        private String toolName;
        /** 仅在 invoking 阶段 */
        private Object arguments;
        /** 仅在 result 阶段 */
        private Object result;
        /** 工具执行失败时的错误信息 */
        private String error;
    }
}
