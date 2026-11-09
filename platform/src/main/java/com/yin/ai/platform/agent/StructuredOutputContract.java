package com.yin.ai.platform.agent;

import java.util.Map;

/**
 * 结构化输出契约 — 把"提示词限定 LLM 输出 JSON"收敛为工具 JSON Schema 契约。
 * <p>
 * 通过 {@code toolName} + {@code jsonSchema} 描述目标结构：运行时注册一个 capture-only 工具，
 * 模型必须按 schema 调用该工具并携带完整参数，结构由模型层保证（不再依赖提示词自觉）。
 * 工具参数即结果 JSON，与 {@link AgentConfig#outputContract()} 搭配注入 ReAct 循环。
 *
 * @param toolName   捕获工具名（唯一，默认 {@value #DEFAULT_TOOL_NAME}）
 * @param jsonSchema 结果结构的 JSON Schema（OpenAI function tool 的 inputSchema）
 */
public record StructuredOutputContract(String toolName, Map<String, Object> jsonSchema) {

    /** 默认捕获工具名。 */
    public static final String DEFAULT_TOOL_NAME = "submitResult";

    /** 默认工具描述：capture-only，完成任务后调用并提交结果 JSON。 */
    public static final String DEFAULT_TOOL_DESCRIPTION = "提交最终结果。完成任务后必须调用本工具提交最终结果，"
            + "工具参数即为结果 JSON，不要输出任何文字或 Markdown。";

    public static StructuredOutputContract of(Map<String, Object> jsonSchema) {
        return new StructuredOutputContract(DEFAULT_TOOL_NAME, jsonSchema);
    }
}
