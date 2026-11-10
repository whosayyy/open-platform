package com.yin.ai.platform.core.agent.pattern;

/** Semantic step category used by model routing and reasoning policy. */
public enum StepType {
    PLANNING,
    SEARCH,
    RAG_RETRIEVAL,
    EVIDENCE_ANALYSIS,
    SYNTHESIS,
    CONTENT_GENERATION,
    FACT_CHECK,
    TOOL;

    public static StepType infer(String executor, String description) {
        String value = ((executor == null ? "" : executor) + " "
                + (description == null ? "" : description)).toLowerCase(java.util.Locale.ROOT);
        if (value.contains("web_search") || value.contains("search") || value.contains("搜索")) return SEARCH;
        if (value.contains("rag") || value.contains("知识库") || value.contains("检索")) return RAG_RETRIEVAL;
        if (value.contains("fact") || value.contains("verify") || value.contains("核查")) return FACT_CHECK;
        if (value.contains("synth") || value.contains("总结") || value.contains("综合")) return SYNTHESIS;
        if (value.contains("analysis") || value.contains("分析")) return EVIDENCE_ANALYSIS;
        if (value.contains("write") || value.contains("draft") || value.contains("撰写")) return CONTENT_GENERATION;
        return "llm".equalsIgnoreCase(executor) ? CONTENT_GENERATION : TOOL;
    }
}
