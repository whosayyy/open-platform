package com.yin.ai.platform.workflow.executor;

/**
 * 一次确定性工具调用的结果（供研究步骤汇总）。
 */
public record ResearchToolResult(
        String query,
        String tool,
        Object output,
        boolean success,
        String error
) {

    public static ResearchToolResult ok(String query, String tool, Object output) {
        return new ResearchToolResult(query, tool, output, true, null);
    }

    public static ResearchToolResult fail(String query, String tool, String error) {
        return new ResearchToolResult(query, tool, null, false, error);
    }
}
