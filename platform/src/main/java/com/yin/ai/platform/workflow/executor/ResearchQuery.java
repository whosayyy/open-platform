package com.yin.ai.platform.workflow.executor;

/**
 * 研究查询 — 由模型生成（query + freshnessType），程序负责转换为具体工具参数（days）。
 */
public record ResearchQuery(String query, String freshnessType) {

    public String freshnessTypeOrDefault() {
        return freshnessType == null || freshnessType.isBlank() ? "GENERAL_UPDATE" : freshnessType;
    }
}
