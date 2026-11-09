package com.yin.ai.platform.gateway.health;

import java.time.Instant;

/**
 * 单个模型的熔断器状态机 — 三态：CLOSED → OPEN → HALF_OPEN → CLOSED。
 */
public class CircuitBreakerState {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private volatile int consecutiveFailures;
    private volatile long lastFailureTimeMs;
    private volatile long openedAtMs;
    private final int failureThreshold;
    private final long recoveryWaitMs;

    public CircuitBreakerState(int failureThreshold, long recoveryWaitMs) {
        this.failureThreshold = failureThreshold;
        this.recoveryWaitMs = recoveryWaitMs;
    }

    public State getState() { return state; }
    public int getConsecutiveFailures() { return consecutiveFailures; }

    /** 记录成功 — 恢复或推进状态 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
        }
    }

    /** 记录失败 — 可能触发熔断 */
    public synchronized boolean recordFailure() {
        consecutiveFailures++;
        lastFailureTimeMs = System.currentTimeMillis();
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAtMs = lastFailureTimeMs;
            return true; // just tripped
        }
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAtMs = lastFailureTimeMs;
            return true; // re-opened
        }
        return false;
    }

    /** 尝试从 OPEN 过渡到 HALF_OPEN（恢复期已过） */
    public synchronized boolean tryHalfOpen() {
        if (state == State.OPEN && (System.currentTimeMillis() - openedAtMs) >= recoveryWaitMs) {
            state = State.HALF_OPEN;
            return true;
        }
        return false;
    }

    /** 是否允许请求通过 */
    public boolean allowsRequest() {
        if (state == State.CLOSED || state == State.HALF_OPEN) return true;
        return tryHalfOpen();
    }

    public void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        lastFailureTimeMs = 0;
        openedAtMs = 0;
    }

    /** 从 Redis 恢复状态时使用 */
    public void setState(State state) { this.state = state; }

    /** 从 Redis 恢复失败计数时使用 */
    public void setConsecutiveFailures(long failures) {
        this.consecutiveFailures = (int) failures;
    }

    /** 标记 openedAt 为已计算（从 Redis 恢复时不重置） */
    public void resetOpenedAt() { this.openedAtMs = 0; }
}
