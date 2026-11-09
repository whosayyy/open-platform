package com.yin.ai.platform.core.memory.working;

import java.time.Instant;
import java.util.Map;

/**
 * 中间结果 — PlanNode 执行过程中产生的输出。
 * 通过 planNodeId 关联到具体步骤，支持带类型的 data 访问。
 */
public record IntermediateResult(
        String resultId,
        String planNodeId,
        Object data,
        Class<?> dataType,
        Instant producedAt,
        Map<String, String> metadata) {

    public IntermediateResult {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    @SuppressWarnings("unchecked")
    public <T> T getData(Class<T> type) {
        if (type.isInstance(data)) {
            return (T) data;
        }
        throw new ClassCastException("Cannot cast " + dataType.getName() + " to " + type.getName());
    }
}
