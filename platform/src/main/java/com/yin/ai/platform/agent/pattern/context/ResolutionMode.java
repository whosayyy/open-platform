package com.yin.ai.platform.agent.pattern.context;

/**
 * 步骤间数据解析模式 — 决定前序步骤输出如何注入当前步骤的 Prompt。
 * <p>
 * 设计理由：不是所有前序输出都需要全量传入。
 * 一个搜索结果可能 10KB，但下一步只需要摘要。
 * 通过 ResolutionMode 控制注入粒度，节省 token。
 */
public enum ResolutionMode {

    /** 全量内联：将前序输出完整嵌入 Prompt（适合小数据 < 2KB） */
    INLINE,

    /** 摘要注入：LLM 先摘要再注入（适合大数据 > 2KB） */
    SUMMARY,

    /** 引用模式：仅传入引用标识，执行时按需读取（适合文件/URL） */
    REFERENCE,

    /** 记忆检索：从 Memory 系统检索相关历史（跨任务知识） */
    MEMORY
}
