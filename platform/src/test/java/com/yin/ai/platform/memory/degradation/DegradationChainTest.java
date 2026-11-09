package com.yin.ai.platform.memory.degradation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DegradationChain")
class DegradationChainTest {

    private DegradationChain chain;

    @BeforeEach
    void setUp() {
        chain = new DegradationChain();
    }

    @Test
    @DisplayName("正常执行返回结果")
    void shouldExecuteSuccessfully() {
        String result = chain.execute(
                DegradationPolicy.semanticStore(),
                () -> "success",
                "fallback");
        assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("异常时返回 fallback")
    void shouldReturnFallbackOnError() {
        String result = chain.execute(
                DegradationPolicy.semanticStore(),
                () -> { throw new RuntimeException("boom"); },
                "fallback");
        assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("FAIL_FAST 策略抛出 DegradationException")
    void shouldThrowOnFailFast() {
        DegradationPolicy failFast = DegradationPolicy.episodicStore();
        assertThatThrownBy(() -> chain.execute(failFast,
                () -> { throw new RuntimeException("critical"); },
                "fallback"))
                .isInstanceOf(DegradationException.class)
                .hasMessageContaining("Component failed");
    }

    @Test
    @DisplayName("连续失败达到阈值后触发熔断")
    void shouldOpenCircuitAfterConsecutiveFailures() {
        DegradationPolicy policy = new DegradationPolicy("test", DegradationPolicy.FailureAction.SKIP,
                Duration.ofSeconds(10), 3, 30);
        AtomicInteger calls = new AtomicInteger();

        Callable<String> failing = () -> {
            calls.incrementAndGet();
            throw new RuntimeException("fail");
        };

        // 连续失败 3 次
        for (int i = 0; i < 3; i++) {
            chain.execute(policy, failing, "fallback");
        }
        assertThat(calls.get()).isEqualTo(3);

        // 第 4 次：熔断已打开，直接走 fallback，不调用 operation
        chain.execute(policy, failing, "fallback");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("成功后重置失败计数")
    void shouldResetOnSuccess() {
        DegradationPolicy policy = new DegradationPolicy("test", DegradationPolicy.FailureAction.SKIP,
                Duration.ofSeconds(10), 3, 30);
        AtomicInteger calls = new AtomicInteger();

        // 失败 1 次
        chain.execute(policy, () -> { calls.incrementAndGet(); throw new RuntimeException("fail"); }, "fallback");
        // 成功 1 次 → 重置
        String result = chain.execute(policy, () -> { calls.incrementAndGet(); return "ok"; }, "fallback");
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);

        // 再来一次失败，不会触发熔断（因为之前重置了）
        chain.execute(policy, () -> { calls.incrementAndGet(); throw new RuntimeException("fail2"); }, "fallback");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("resetAll 清空所有熔断状态")
    void shouldResetAllCircuits() {
        DegradationPolicy policy = new DegradationPolicy("test", DegradationPolicy.FailureAction.SKIP,
                Duration.ofSeconds(10), 2, 30);

        for (int i = 0; i < 2; i++) {
            chain.execute(policy, () -> { throw new RuntimeException("fail"); }, "fallback");
        }
        chain.resetAll();

        AtomicInteger calls = new AtomicInteger();
        String result = chain.execute(policy, () -> { calls.incrementAndGet(); return "recovered"; }, "fallback");
        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("reset(componentName) 重置指定组件")
    void shouldResetSpecificComponent() {
        DegradationPolicy policy = new DegradationPolicy("comp-A", DegradationPolicy.FailureAction.SKIP,
                Duration.ofSeconds(10), 2, 30);

        for (int i = 0; i < 2; i++) {
            chain.execute(policy, () -> { throw new RuntimeException("fail"); }, "fallback");
        }
        chain.reset("comp-A");

        AtomicInteger calls = new AtomicInteger();
        chain.execute(policy, () -> { calls.incrementAndGet(); return "ok"; }, "fallback");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("独立组件的熔断互不影响")
    void shouldIsolateComponents() {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();

        DegradationPolicy policyA = new DegradationPolicy("A", DegradationPolicy.FailureAction.SKIP,
                Duration.ofSeconds(10), 2, 30);
        DegradationPolicy policyB = new DegradationPolicy("B", DegradationPolicy.FailureAction.SKIP,
                Duration.ofSeconds(10), 2, 30);

        // A 熔断
        for (int i = 0; i < 2; i++) {
            chain.execute(policyA, () -> { callsA.incrementAndGet(); throw new RuntimeException("failA"); }, "fallback");
        }
        chain.execute(policyA, () -> { callsA.incrementAndGet(); throw new RuntimeException("failA"); }, "fallback");
        assertThat(callsA.get()).isEqualTo(2); // 操作未被调用

        // B 不受影响
        String result = chain.execute(policyB, () -> { callsB.incrementAndGet(); return "b-ok"; }, "fallback");
        assertThat(result).isEqualTo("b-ok");
        assertThat(callsB.get()).isEqualTo(1);
    }
}
