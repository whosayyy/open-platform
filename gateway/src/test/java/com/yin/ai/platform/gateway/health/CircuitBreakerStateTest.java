package com.yin.ai.platform.gateway.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreakerState — 熔断器状态机")
class CircuitBreakerStateTest {

    private CircuitBreakerState breaker;

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreakerState(3, 1000);
    }

    @Test
    @DisplayName("初始状态为 CLOSED")
    void shouldStartClosed() {
        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    @DisplayName("成功调用重置失败计数")
    void shouldResetOnSuccess() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.State.CLOSED);
    }

    @Test
    @DisplayName("连续失败达到阈值时熔断打开")
    void shouldOpenOnThresholdBreach() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.State.CLOSED);
        boolean tripped = breaker.recordFailure();
        assertThat(tripped).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.State.OPEN);
    }

    @Test
    @DisplayName("熔断打开后拒绝请求")
    void shouldRejectInOpenState() {
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        assertThat(breaker.allowsRequest()).isFalse();
    }

    @Test
    @DisplayName("恢复期过后进入半开状态")
    void shouldHalfOpenAfterRecovery() throws InterruptedException {
        CircuitBreakerState fastBreaker = new CircuitBreakerState(1, 10);
        fastBreaker.recordFailure();
        assertThat(fastBreaker.getState()).isEqualTo(CircuitBreakerState.State.OPEN);

        Thread.sleep(20);
        boolean allowed = fastBreaker.allowsRequest();
        assertThat(allowed).isTrue();
        assertThat(fastBreaker.getState()).isEqualTo(CircuitBreakerState.State.HALF_OPEN);
    }

    @Test
    @DisplayName("半开状态成功则关闭")
    void shouldCloseOnSuccessInHalfOpen() throws InterruptedException {
        CircuitBreakerState fastBreaker = new CircuitBreakerState(1, 10);
        fastBreaker.recordFailure();
        Thread.sleep(20);
        fastBreaker.tryHalfOpen();
        assertThat(fastBreaker.getState()).isEqualTo(CircuitBreakerState.State.HALF_OPEN);
        fastBreaker.recordSuccess();
        assertThat(fastBreaker.getState()).isEqualTo(CircuitBreakerState.State.CLOSED);
    }

    @Test
    @DisplayName("半开状态失败则重新打开")
    void shouldReopenOnFailureInHalfOpen() throws InterruptedException {
        CircuitBreakerState fastBreaker = new CircuitBreakerState(1, 10);
        fastBreaker.recordFailure();
        Thread.sleep(20);
        fastBreaker.tryHalfOpen();
        fastBreaker.recordFailure();
        assertThat(fastBreaker.getState()).isEqualTo(CircuitBreakerState.State.OPEN);
    }

    @Test
    @DisplayName("reset 恢复初始状态")
    void shouldResetToInitial() {
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        breaker.reset();
        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
        assertThat(breaker.allowsRequest()).isTrue();
    }
}
