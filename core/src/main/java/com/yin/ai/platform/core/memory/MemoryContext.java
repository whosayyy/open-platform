package com.yin.ai.platform.core.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Memory 操作上下文。外围适配器负责把 Web、Security 或旧执行引擎上下文映射为该值对象。
 * 遵循不可变 record + with* 派生方法，不依赖框架或旧引擎实现。
 */
public record MemoryContext(
        String traceId,
        String conversationId,
        Long userId,
        String agentId,
        String operation,
        Instant createdAt,
        Map<String, Object> attributes) {

    public MemoryContext {
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public static MemoryContext empty() {
        return new MemoryContext(null, null, null, null, null, Instant.now(), Map.of());
    }

    public MemoryContext withAgentId(String agentId) {
        return new MemoryContext(traceId, conversationId, userId, agentId,
                operation, createdAt, attributes);
    }

    public MemoryContext withOperation(String operation) {
        return new MemoryContext(traceId, conversationId, userId, agentId,
                operation, createdAt, attributes);
    }

    /** [全链路追踪] 设置 traceId（与 ExecutionContext/MDC 对齐，供审计日志 join 任务） */
    public MemoryContext withTraceId(String newTraceId) {
        return new MemoryContext(newTraceId, conversationId, userId, agentId,
                operation, createdAt, attributes);
    }

    public MemoryContext withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(key, value);
        return new MemoryContext(traceId, conversationId, userId, agentId,
                operation, createdAt, newAttrs);
    }

    public Optional<Long> getUserId() {
        return Optional.ofNullable(userId);
    }

    public Optional<String> getAgentId() {
        return Optional.ofNullable(agentId);
    }
}
