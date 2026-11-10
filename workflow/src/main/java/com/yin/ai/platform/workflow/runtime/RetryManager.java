package com.yin.ai.platform.workflow.runtime;

import com.yin.ai.platform.workflow.model.RetryPolicy;
import com.yin.ai.platform.workflow.model.StepRun;
import org.springframework.stereotype.Component;

/**
 * 重试策略管理器 — 决定步骤失败后是否重试及退避时长。
 */
@Component
public class RetryManager {

    /** 退避上限 30s，防止长时间挂起 */
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    /**
     * 步骤是否仍可重试（attempt 为已尝试次数）。
     */
    public boolean shouldRetry(StepRun stepRun, RetryPolicy policy) {
        if (policy == null) {
            return false;
        }
        return stepRun.getAttempt() < policy.getMaxAttempts();
    }

    /**
     * 第 N 次重试前的退避时长（指数退避，上限 30s）。
     */
    public long backoffMillis(StepRun stepRun, RetryPolicy policy) {
        if (policy == null) {
            return 0;
        }
        long base = Math.max(policy.getBackoffMillis(), 0);
        int exponent = Math.max(stepRun.getAttempt() - 1, 0);
        long delay = base * (1L << Math.min(exponent, 8));
        return Math.min(delay, MAX_BACKOFF_MILLIS);
    }
}
