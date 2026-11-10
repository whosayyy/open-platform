package com.yin.ai.platform.workflow.executor;

import com.yin.ai.platform.workflow.model.Artifact;

/**
 * 步骤执行结果。
 */
public record StepExecutionResult(
        boolean success,
        Artifact output,
        String errorCode,
        String errorMessage,
        boolean needInput,
        String waitingReason
) {

    public static StepExecutionResult ok(Artifact output) {
        return new StepExecutionResult(true, output, null, null, false, null);
    }

    public static StepExecutionResult fail(String code, String message) {
        return new StepExecutionResult(false, null, code, message, false, null);
    }

    public static StepExecutionResult needInput(String reason) {
        return new StepExecutionResult(true, null, null, null, true, reason);
    }
}
