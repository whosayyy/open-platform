package com.yin.ai.platform.gateway.filter;

/**
 * 重试耗尽异常 — RetryFilter 重试全部失败后抛出，
 * 由 FallbackFilter 捕获并执行降级策略。
 */
public class RetryExhaustedException extends RuntimeException {
    private final int retryCount;

    public RetryExhaustedException(Throwable cause, int retryCount) {
        super("重试已耗尽 (" + retryCount + " 次)", cause);
        this.retryCount = retryCount;
    }

    public int getRetryCount() { return retryCount; }
}
