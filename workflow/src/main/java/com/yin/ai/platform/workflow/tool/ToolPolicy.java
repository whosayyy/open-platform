package com.yin.ai.platform.workflow.tool;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 步骤工具策略 — 决定当前步骤允许注入模型的工具集。
 *
 * <p>未授权的工具不会进入模型的 Tool Schema（而非仅通过 Prompt 告知）。
 */
public record ToolPolicy(String name, Set<String> allowedTools) {

    public static ToolPolicy of(String name, Set<String> allowedTools) {
        return new ToolPolicy(name, allowedTools == null ? new LinkedHashSet<>() : allowedTools);
    }

    public static ToolPolicy empty(String name) {
        return new ToolPolicy(name, new LinkedHashSet<>());
    }

    public boolean allows(String toolName) {
        return allowedTools != null && allowedTools.contains(toolName);
    }

    public Set<String> allowedToolsOrEmpty() {
        return allowedTools == null ? new LinkedHashSet<>() : allowedTools;
    }
}
