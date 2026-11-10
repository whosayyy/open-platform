package com.yin.ai.platform.workflow.executor;

/**
 * 应用步骤处理结果。
 */
public record HandlerResult(
        boolean success,
        Object data,
        String errorCode,
        String errorMessage
) {

    public static HandlerResult ok(Object data) {
        return new HandlerResult(true, data, null, null);
    }

    public static HandlerResult fail(String code, String message) {
        return new HandlerResult(false, null, code, message);
    }
}
