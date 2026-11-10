package com.yin.ai.platform.engine;

import com.yin.ai.platform.core.runtime.execution.ExecutionEvent;
import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import com.yin.ai.platform.core.runtime.execution.ExecutionResult;
import com.yin.ai.platform.core.runtime.execution.RetryPolicy;
import com.yin.ai.platform.engine.config.DistributedConcurrencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认执行引擎实现（Java 21 虚拟线程版）。
 * <p>
 * 使用 {@link Executor}（虚拟线程 per-task executor）管理执行。
 * 并发控制由 {@link DistributedConcurrencyGuard}（本地 Semaphore + Redis 全局计数器）提供背压。
 * <p>
 * 采用模板模式+AOP思想：上下文注入、日志、耗时统计、异常处理、
 * 统一返回结构这些横切关注点集中在一个地方，真正的 AI 能力
 * （如调用模型、执行 Tool、查询 RAG 等）只需通过 {@code Callable<T>} 提供。
 */
@Slf4j
public class DefaultExecutionEngine implements ExecutionEngine {

    private final Executor executor;
    private final DistributedConcurrencyGuard concurrencyGuard;
    private final AtomicLong taskIdGen = new AtomicLong(0);

    public DefaultExecutionEngine(Executor executor, DistributedConcurrencyGuard concurrencyGuard) {
        this.executor = executor;
        this.concurrencyGuard = concurrencyGuard;
    }

    @Override
    public <T> ExecutionResult<T> execute(ExecutionContext ctx, Callable<T> task, ExecutionOptions options) {
        String opName = options.operationName();

        long start = System.nanoTime();
        long timeoutMs = options.timeout() != null ? options.timeout().toMillis() : 30000;

        // 并发防护：获取信号量许可
        boolean acquired = concurrencyGuard.tryAcquire(timeoutMs);
        if (!acquired) {
            Duration dur = Duration.ofNanos(System.nanoTime() - start);
            return ExecutionResult.fail(
                    new IllegalStateException("engine concurrency limit reached"), dur, 0);
        }

        log.debug("[ENGINE] op={} status=START timeoutMs={} traceId={}",
                opName, timeoutMs, ctx.traceId());

        // 任务在独立虚拟线程上执行，调用线程用 Future.get(timeout) 等待。
        // 超时后 cancel(true) 中断工作线程，保证 options.timeout() 真正生效；
        // 调用线程不再绑定 ExecutionContext，避免污染调用方线程的 MDC / SecurityContext。
        FutureTask<T> future = new FutureTask<>(() -> runInContext(ctx, task));
        Thread.ofVirtual()
                .name("engine-task-" + taskIdGen.incrementAndGet())
                .start(future);

        try {
            T result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            log.debug("[ENGINE] op={} status=END durationMs={} traceId={}",
                    opName, duration.toMillis(), ctx.traceId());
            return ExecutionResult.ok(result, duration, 0);
        } catch (TimeoutException e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            // 只有声明为可取消的任务才中断；否则让它自然结束（超时仍对调用方生效）
            boolean cancelled = options.cancellable() && future.cancel(true);
            log.warn("[ENGINE] op={} status=TIMEOUT durationMs={} timeoutMs={} interrupted={} traceId={}",
                    opName, duration.toMillis(), timeoutMs, cancelled, ctx.traceId());
            return ExecutionResult.fail(
                    new TimeoutException("operation '" + opName + "' exceeded timeout of "
                            + timeoutMs + "ms"), duration, 0);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            log.warn("[ENGINE] op={} status=INTERRUPTED durationMs={} traceId={}",
                    opName, duration.toMillis(), ctx.traceId());
            return ExecutionResult.fail(e, duration, 0);
        } catch (ExecutionException e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[ENGINE] op={} status=FAIL durationMs={} traceId={}",
                    opName, duration.toMillis(), ctx.traceId(), cause);
            return ExecutionResult.fail(cause, duration, 0);
        } finally {
            concurrencyGuard.release();
        }
    }

    /**
     * 在工作线程上绑定 ExecutionContext 并执行任务。
     * <p>
     * MDC / SecurityContext 的注入与清理都发生在这个线程上，因此不会影响调用方线程。
     */
    private <T> T runInContext(ExecutionContext ctx, Callable<T> task) throws Exception {
        injectContext(ctx);
        try {
            return ExecutionContextHolder.runWith(ctx, task);
        } finally {
            clearContext();
        }
    }

    @Override
    public <T> CompletableFuture<ExecutionResult<T>> executeAsync(
            ExecutionContext ctx, Callable<T> task, ExecutionOptions options) {
        return CompletableFuture.supplyAsync(() -> execute(ctx, task, options), executor);
    }

    @Override
    public <T> List<ExecutionResult<T>> executeParallel(
            ExecutionContext ctx, List<Callable<T>> tasks, ExecutionOptions options) {
        long start = System.nanoTime();

        record TaskResult<T>(T data, Duration duration, Throwable error) { }

        try (var scope = new StructuredTaskScope<TaskResult<T>>("engine-parallel",
                Thread.ofVirtual().name("engine-task-", 1).factory()) {
            @Override
            protected void handleComplete(Subtask<? extends TaskResult<T>> subtask) {
                // no-op: tasks handle their own errors, we collect results below
            }
        }) {
            List<Subtask<TaskResult<T>>> subtasks = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                subtasks.add(scope.fork(() -> {
                    injectContext(ctx);
                    long taskStart = System.nanoTime();
                    boolean acquired = concurrencyGuard.tryAcquire(
                            options.timeout() != null ? options.timeout().toMillis() : 10000);
                    if (!acquired) {
                        return new TaskResult<>(null,
                                Duration.ofNanos(System.nanoTime() - taskStart),
                                new IllegalStateException("parallel task: concurrency limit reached"));
                    }
                    try {
                        T result = ExecutionContextHolder.runWith(ctx, task);
                        return new TaskResult<>(result,
                                Duration.ofNanos(System.nanoTime() - taskStart), null);
                    } catch (Exception e) {
                        return new TaskResult<>(null,
                                Duration.ofNanos(System.nanoTime() - taskStart), e);
                    } finally {
                        concurrencyGuard.release();
                        clearContext();
                    }
                }));
            }

            try {
                scope.joinUntil(Instant.now().plusMillis(
                        options.timeout() != null ? options.timeout().toMillis() : 60000));
            } catch (TimeoutException e) {
                // scope close() will cancel remaining tasks
            }

            List<ExecutionResult<T>> results = new ArrayList<>(subtasks.size());
            for (Subtask<TaskResult<T>> st : subtasks) {
                if (st.state() == Subtask.State.SUCCESS) {
                    TaskResult<T> tr = st.get();
                    if (tr.error() != null) {
                        results.add(ExecutionResult.fail(tr.error(), tr.duration(), 0));
                    } else {
                        results.add(ExecutionResult.ok(tr.data(), tr.duration(), 0));
                    }
                } else {
                    results.add(ExecutionResult.fail(
                            new TimeoutException("task cancelled or not completed"),
                            Duration.ZERO, 0));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            log.error("[ENGINE] op={} status=INTERRUPTED traceId={}",
                    options.operationName(), ctx.traceId());
            return List.of(ExecutionResult.fail(e, duration, 0));
        } catch (Exception e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            log.error("[ENGINE] op={} status=PARALLEL_FAIL traceId={}",
                    options.operationName(), ctx.traceId(), e);
            return List.of(ExecutionResult.fail(e, duration, 0));
        }
    }

    @Override
    public <T> Flux<ExecutionEvent<T>> executeStream(
            ExecutionContext ctx, StreamTask<T> task, ExecutionOptions options) {
        return Flux.create((FluxSink<ExecutionEvent<T>> sink) -> {
            injectContext(ctx);
            try {
                task.execute(sink, ctx);
            } catch (Exception e) {
                sink.error(e);
            } finally {
                clearContext();
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public <T> ExecutionResult<T> executeWithRetry(
            ExecutionContext ctx, Callable<T> task, ExecutionOptions options, RetryPolicy policy) {
        int attempts = 0;
        Throwable lastError = null;

        while (attempts <= policy.maxRetries()) {
            ExecutionResult<T> result = execute(ctx, task, options);
            if (result.success()) {
                return result;
            }

            lastError = result.error();

            if (attempts < policy.maxRetries()
                    && policy.retryableFilter().test(lastError)) {
                attempts++;
                Duration backoff = policy.backoffForAttempt(attempts - 1);
                log.warn("[ENGINE] op={} retry={}/{} backoffMs={} traceId={} reason={}",
                        options.operationName(), attempts, policy.maxRetries(),
                        backoff.toMillis(), ctx.traceId(), lastError.getMessage());
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ExecutionResult.fail(ie, Duration.ZERO, attempts);
                }
            } else {
                break;
            }
        }
        return ExecutionResult.fail(lastError, Duration.ZERO, attempts);
    }

    // ==================== 上下文注入/清理 ====================

    private void injectContext(ExecutionContext ctx) {
        if (ctx.securityContext() != null) {
            SecurityContextHolder.setContext(ctx.securityContext());
        }
        if (ctx.mdcContext() != null) {
            MDC.setContextMap(ctx.mdcContext());
        }
        MDC.put("traceId", ctx.traceId());
        if (ctx.conversationId() != null) {
            MDC.put("conversationId", ctx.conversationId());
        }
        if (ctx.caller() != null) {
            MDC.put("caller", ctx.caller());
        }
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }
}
