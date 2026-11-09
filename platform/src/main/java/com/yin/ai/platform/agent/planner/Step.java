package com.yin.ai.platform.agent.planner;

import java.util.List;
import java.util.Map;

/**
 * 单个执行步骤。
 *
 * @param id           步骤唯一标识
 * @param description  步骤描述
 * @param tool         使用的工具名（null = LLM 直接处理）
 * @param toolParams   工具参数
 * @param dependsOn    前置步骤 ID 列表
 * @param reason       [UI 优化] LLM 给出的"为什么这么做"（展示给用户理解规划意图）
 */
public record Step(
        String id,
        String description,
        String tool,
        Map<String, Object> toolParams,
        List<String> dependsOn,
        String reason) {

    public static Step of(String id, String description) {
        return new Step(id, description, null, null, null, null);
    }

    public static Step tool(String id, String description, String toolName, Map<String, Object> params) {
        return new Step(id, description, toolName, params, null, null);
    }

    public boolean isToolCall() {
        return tool != null;
    }
}
