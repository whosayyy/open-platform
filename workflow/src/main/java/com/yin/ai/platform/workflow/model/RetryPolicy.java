package com.yin.ai.platform.workflow.model;

/**
 * 步骤重试策略。
 */
public class RetryPolicy {

    private int maxAttempts = 1;
    private long backoffMillis = 1000;

    public RetryPolicy() {
    }

    public RetryPolicy(int maxAttempts, long backoffMillis) {
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffMillis() {
        return backoffMillis;
    }

    public void setBackoffMillis(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }
}
