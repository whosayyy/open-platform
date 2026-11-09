package com.yin.ai.platform.engine;

import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import com.yin.ai.platform.core.runtime.execution.ExecutionResult;
import com.yin.ai.platform.engine.config.DistributedConcurrencyGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行引擎的关键契约测试。
 * <p>
 * 重点覆盖 {@link ExecutionOptions#timeout()} 是否真正生效——
 * 这是引擎最重要的对外承诺，也是历史上完全缺失的能力。
 */
@DisplayName("DefaultExecutionEngine")
class DefaultExecutionEngineTest {

    private static final Executor RUNNER = Runnable::run;

    private ExecutionContext ctx() {
        return new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    private DefaultExecutionEngine engine() {
        return new DefaultExecutionEngine(RUNNER, new DistributedConcurrencyGuard(16));
    }

    @Test
    @DisplayName("任务超时后按超时失败返回，而不是无限阻塞调用方")
    void executeHonoursTimeout() {
        DefaultExecutionEngine engine = engine();
        int timeoutMs = 200;

        long start = System.currentTimeMillis();
        ExecutionResult<String> result = engine.execute(
                ctx(),
                () -> {
                    Thread.sleep(10_000);
                    return "should never be returned";
                },
                ExecutionOptions.of("slow-op", Duration.ofMillis(timeoutMs)));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isInstanceOf(TimeoutException.class);
        assertThat(result.error().getMessage()).contains("slow-op").contains(String.valueOf(timeoutMs));
        // 必须在超时附近返回，而不是等任务自己睡完 10s
        assertThat(elapsed).isLessThan(5_000);
    }

    @Test
    @DisplayName("超时后中断工作线程，任务不会继续空转")
    void executeInterruptsTaskOnTimeout() throws InterruptedException {
        DefaultExecutionEngine engine = engine();
        AtomicBoolean interrupted = new AtomicBoolean(false);

        ExecutionResult<String> result = engine.execute(
                ctx(),
                () -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        throw e;
                    }
                    return "done";
                },
                ExecutionOptions.of("interrupt-op", Duration.ofMillis(150)));

        assertThat(result.success()).isFalse();
        // 给中断信号一点传播时间
        for (int i = 0; i < 20 && !interrupted.get(); i++) {
            Thread.sleep(50);
        }
        assertThat(interrupted).as("工作线程应被中断").isTrue();
    }

    @Test
    @DisplayName("正常任务不受超时影响，返回成功结果")
    void executeReturnsResultWithinTimeout() {
        DefaultExecutionEngine engine = engine();

        ExecutionResult<String> result = engine.execute(
                ctx(), () -> "ok", ExecutionOptions.of("fast-op", Duration.ofSeconds(5)));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("ok");
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("任务异常按失败返回并保留原始异常")
    void executeSurfacesTaskFailure() {
        DefaultExecutionEngine engine = engine();
        Callable<String> failing = () -> {
            throw new IllegalStateException("boom");
        };

        ExecutionResult<String> result = engine.execute(
                ctx(), failing, ExecutionOptions.of("fail-op", Duration.ofSeconds(5)));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    @DisplayName("执行上下文在任务内部可见，且不污染调用方线程")
    void executeBindsContextOnWorkerThreadOnly() {
        DefaultExecutionEngine engine = engine();
        ExecutionContext ctx = ctx();

        ExecutionResult<Boolean> result = engine.execute(
                ctx,
                () -> ExecutionContextHolder.getOptional().isPresent(),
                ExecutionOptions.of("ctx-op", Duration.ofSeconds(5)));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).as("工作线程上应能读到 ExecutionContext").isTrue();
        // 调用方线程（当前测试线程）不应残留绑定或 MDC
        assertThat(ExecutionContextHolder.getOptional()).isEmpty();
    }

    @Test
    @DisplayName("并发受限时快速失败，不占用超时预算")
    void executeFailsFastWhenConcurrencyExhausted() {
        DefaultExecutionEngine engine = new DefaultExecutionEngine(RUNNER, new DistributedConcurrencyGuard(1));
        // 先占满唯一许可
        engine.execute(ctx(), () -> {
            ExecutionResult<String> blocked = engine.execute(
                    ctx(), () -> "never", ExecutionOptions.of("blocked-op", Duration.ofMillis(100)));
            assertThat(blocked.success()).isFalse();
            assertThat(blocked.error()).hasMessageContaining("concurrency limit");
            return "held";
        }, ExecutionOptions.of("holder-op", Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("并行执行汇总每个子任务的结果")
    void executeParallelCollectsResults() {
        DefaultExecutionEngine engine = engine();

        List<ExecutionResult<String>> results = engine.executeParallel(
                ctx(),
                List.of(() -> "a", () -> "b", () -> {
                    throw new IllegalStateException("c failed");
                }),
                ExecutionOptions.of("parallel-op", Duration.ofSeconds(5)));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).data()).isEqualTo("a");
        assertThat(results.get(1).data()).isEqualTo("b");
        assertThat(results.get(2).success()).isFalse();
    }

    @Test
    @DisplayName("带重试执行：可重试异常会重试并在成功时返回")
    void executeWithRetrySucceedsAfterRetry() {
        DefaultExecutionEngine engine = engine();
        int[] attempts = {0};

        ExecutionResult<String> result = engine.executeWithRetry(
                ctx(),
                () -> {
                    attempts[0]++;
                    if (attempts[0] < 2) {
                        throw new java.io.IOException("transient");
                    }
                    return "recovered";
                },
                ExecutionOptions.of("retry-op", Duration.ofSeconds(5)),
                com.yin.ai.platform.core.runtime.execution.RetryPolicy.standard());

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("recovered");
        assertThat(attempts[0]).isEqualTo(2);
    }
}
