package com.yin.ai.platform.agent.pattern.research;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;

import java.util.List;

/**
 * 研究综合政策 — 综合阶段的来源收集、格式化与「来源门控」校验。
 * <p>
 * 设计理由：LLM 生成的报告可能引用并未检索到的来源/URL。本政策把「已验证来源」
 * 显式注入综合 Prompt，并在生成后确定性清洗未出现在已验证来源中的 URL，
 * 防止模型编造引用。
 */
public interface ResearchSynthesisPolicy {

    /** 收集已完成 web_search 步骤的已验证来源 */
    List<SourceRef> collectSources(AgentExecutionContext ctx);

    /** 将来源列表格式化为 markdown 列表，注入综合 Prompt */
    String formatSources(List<SourceRef> sources);

    /**
     * 校验报告：用已验证来源集合替换报告中的未验证 URL。
     *
     * @return {@link Verdict}，含清洗后的报告与未验证 URL 列表
     */
    Verdict verify(String report, List<SourceRef> sources);

    /** 已验证来源（来自 web_search 步骤输出） */
    record SourceRef(String title, String url, String source,
                     String snippet, String publishedDate) {
        public SourceRef(String title, String url, String source) {
            this(title, url, source, "", "");
        }
    }

    /** 校验结果：clean=false 表示存在被替换的未验证 URL */
    record Verdict(boolean clean, String sanitizedReport, List<String> foreignUrls) {}
}
