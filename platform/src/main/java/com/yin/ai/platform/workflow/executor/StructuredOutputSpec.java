package com.yin.ai.platform.workflow.executor;

import java.util.Map;

/**
 * 结构化输出契约 — 通过 capture-only 工具强制模型按 Schema 输出。
 */
public record StructuredOutputSpec(
        String toolName,
        String description,
        Map<String, Object> schema
) {
}
