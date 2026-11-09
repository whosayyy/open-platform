package com.yin.ai.platform.memory.degradation;

/**
 * 降级异常 — 当降级策略为 FAIL_FAST 且组件故障时抛出。
 */
public class DegradationException extends RuntimeException {

    public DegradationException(String message, Throwable cause) {
        super(message, cause);
    }
}
