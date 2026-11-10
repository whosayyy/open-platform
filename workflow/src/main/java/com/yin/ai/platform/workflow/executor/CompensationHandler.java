package com.yin.ai.platform.workflow.executor;

/** Business SPI for undoing a partially committed application step. */
public interface CompensationHandler {

    String handlerName();

    void compensate(StepExecutionContext context, Throwable cause);
}
