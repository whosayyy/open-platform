package com.yin.ai.platform.workflow.executor;

import com.yin.ai.platform.workflow.model.ExecutorType;

/**
 * 步骤执行器 SPI — 一次只执行一个步骤，不控制整个工作流。
 */
public interface StepExecutor {

    /**
     * 本执行器负责的步骤类型。
     */
    ExecutorType type();

    /**
     * 执行当前步骤。
     */
    StepExecutionResult execute(StepExecutionContext context);
}
