package com.yin.ai.platform.gateway;

import java.util.List;
import java.util.Map;

/**
 * AI Gateway 统一响应。
 */
public class GatewayResponse {
    /** LLM 返回的文本内容 */
    private String content;
    /** 强制工具（forcedToolName）调用的参数 JSON；非强制工具场景为 null */
    private String toolCallArguments;
    /** 如果有 Function Calling */
    private List<ToolCall> toolCalls;
    /** Token 用量 */
    private TokenUsage tokenUsage;
    /** 实际使用的模型 */
    private String model;
    /** 结束原因（stop / length / content_filter / null） */
    private String finishReason;
    /** 端到端延迟 */
    private long latencyMs;
    /** traceId 透传 */
    private String traceId;
    /** 首 Token 延迟（仅流式有意义，阻塞调用 = latencyMs） */
    private long ttftMs;
    /** 是否触发了降级 */
    private boolean fallback;
    /** 重试次数 */
    private int retryCount;
    /** 是否命中缓存 */
    private boolean cached;
    /** 扩展元数据 */
    private Map<String, Object> metadata;

    public GatewayResponse() {}

    public GatewayResponse(String content) {
        this.content = content;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getToolCallArguments() { return toolCallArguments; }
    public void setToolCallArguments(String toolCallArguments) { this.toolCallArguments = toolCallArguments; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public TokenUsage getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(TokenUsage tokenUsage) { this.tokenUsage = tokenUsage; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public long getTtftMs() { return ttftMs; }
    public void setTtftMs(long ttftMs) { this.ttftMs = ttftMs; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public boolean isCached() { return cached; }
    public void setCached(boolean cached) { this.cached = cached; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /** Function Calling 结果 */
    public static class ToolCall {
        private String id;
        private String name;
        private String arguments;

        public ToolCall() {}

        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }
}
