package com.yin.ai.platform.workflow.executor;

/**
 * 应用步骤处理器 SPI — 确定性业务操作（保存草稿/发布等），不调用 LLM。
 */
public interface ApplicationStepHandler {

    /**
     * 处理器唯一名称（对应 Workflow 中步骤的 handler 字段）。
     */
    String name();

    /**
     * 执行业务操作。
     */
    HandlerResult execute(StepExecutionContext context);
}
