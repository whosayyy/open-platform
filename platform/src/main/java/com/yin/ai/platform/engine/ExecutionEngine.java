package com.yin.ai.platform.engine;

import com.yin.ai.platform.core.runtime.execution.ExecutionEvent;
import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import com.yin.ai.platform.core.runtime.execution.ExecutionResult;
import com.yin.ai.platform.core.runtime.execution.RetryPolicy;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * 统一执行引擎 — 全系统异步执行能力的唯一提供者。
 * <p>
 * 职责：
 * <ul>
 *   <li>线程管理</li>
 *   <li>上下文传播（ExecutionContext → ThreadLocal 桥接）</li>
 *   <li>超时控制 + 取消</li>
 *   <li>重试编排</li>
 *   <li>并行编排</li>
 * </ul>
 * <p>
 * Skill、Function、RAG、AI Gateway 不得自行创建线程池或 ExecutorService，
 * 所有异步/并行/超时/重试委托给 ExecutionEngine。
 */
public interface ExecutionEngine {

    /**
     * 同步执行（阻塞当前线程）。
     */
    <T> ExecutionResult<T> execute(ExecutionContext ctx, Callable<T> task, ExecutionOptions options);

    /**
     * 异步执行（提交到线程池，立即返回 Future）。
     */
    <T> CompletableFuture<ExecutionResult<T>> executeAsync(
            ExecutionContext ctx, Callable<T> task, ExecutionOptions options);

    /**
     * 并行执行。
     * 任一子任务失败 → 自动取消其余子任务（CompletableFuture.allOf + cancel）。
     */
    <T> List<ExecutionResult<T>> executeParallel(
            ExecutionContext ctx, List<Callable<T>> tasks, ExecutionOptions options);

    /**
     * 流式执行（桥接到 Reactor Flux）。
     */
    <T> Flux<ExecutionEvent<T>> executeStream(
            ExecutionContext ctx, StreamTask<T> task, ExecutionOptions options);

    /**
     * 带重试的执行。
     */
    <T> ExecutionResult<T> executeWithRetry(
            ExecutionContext ctx, Callable<T> task, ExecutionOptions options, RetryPolicy retryPolicy);
}
