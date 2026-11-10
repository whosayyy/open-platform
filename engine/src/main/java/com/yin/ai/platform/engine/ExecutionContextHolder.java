package com.yin.ai.platform.engine;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 虚拟线程安全的上下文持有者（Java 21 ScopedValue）。
 * <p>
 * 使用 {@link java.lang.ScopedValue} 替代 InheritableThreadLocal，
 * 实现 structured concurrency 安全的上下文传递。
 * <p>
 * 用法：
 * <pre>{@code
 * ExecutionContextHolder.runWith(ctx, () -> {
 *     skillRuntime.execute(skillName, userInput);
 * });
 * }</pre>
 */
public final class ExecutionContextHolder {

    private static final ScopedValue<ExecutionContext> CURRENT = ScopedValue.newInstance();

    private ExecutionContextHolder() { }

    /** 获取当前上下文（无绑定时抛异常） */
    public static ExecutionContext get() {
        ExecutionContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No ExecutionContext bound. Call ExecutionContextHolder.runWith(ctx, task) first.");
        }
        return ctx;
    }

    /** 获取当前上下文或 null */
    public static Optional<ExecutionContext> getOptional() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** 在上下文中执行任务，自动清理 */
    public static <T> T runWith(ExecutionContext ctx, Callable<T> task) throws Exception {
        return ScopedValue.where(CURRENT, ctx).call(task);
    }

    /** 在上下文中执行任务，自动清理 */
    public static void runWith(ExecutionContext ctx, Runnable task) {
        ScopedValue.where(CURRENT, ctx).run(task);
    }
}
