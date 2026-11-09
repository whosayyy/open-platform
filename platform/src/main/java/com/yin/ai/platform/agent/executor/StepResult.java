package com.yin.ai.platform.agent.executor;

/**
 * 步骤执行结果。
 *
 * @param success    是否成功
 * @param output     输出内容
 * @param error      错误信息（失败时）
 * @param durationMs 本步耗时（毫秒）
 */
public record StepResult(boolean success, String output, String error, long durationMs) {

    public static StepResult ok(String output, long durationMs) {
        return new StepResult(true, output, null, durationMs);
    }

    public static StepResult fail(String error, long durationMs) {
        return new StepResult(false, null, error, durationMs);
    }
}
