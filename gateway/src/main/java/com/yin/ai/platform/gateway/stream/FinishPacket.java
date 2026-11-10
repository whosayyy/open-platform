package com.yin.ai.platform.gateway.stream;

import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.gateway.TokenUsage;

/**
 * 流式结束包 — 放在最后一个 {@link StreamPacket} 的 content 中。
 * <p>
 * 包含本次流式调用的完整统计信息：Token 用量、模型、完成原因、延迟。
 */
public class FinishPacket {

    private TokenUsage usage;
    private String model;
    private String finishReason;
    private long latencyMs;
    private String traceId;

    public FinishPacket() {}

    public FinishPacket(TokenUsage usage, String model, String finishReason, long latencyMs, String traceId) {
        this.usage = usage;
        this.model = model;
        this.finishReason = finishReason;
        this.latencyMs = latencyMs;
        this.traceId = traceId;
    }

    public TokenUsage getUsage() { return usage; }
    public void setUsage(TokenUsage usage) { this.usage = usage; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
