package com.yin.ai.platform.workflow.model;

/**
 * 步骤执行器类型。
 */
public enum ExecutorType {

    /** 单轮结构化输出 LLM 调用（brief/thesis/outline/draft/review/finalize） */
    LLM,

    /** 多工具调用研究步骤（research），可执行受限工具集 */
    AGENT,

    /** 确定性业务操作（保存草稿/发布等），不调用 LLM */
    APPLICATION
}
