package com.yin.security.util;

import java.util.concurrent.Callable;

/**
 * 虚拟线程安全的用户上下文（Java 21 ScopedValue）。
 * <p>
 * 替代 ThreadLocal，使用 {@link java.lang.ScopedValue} 实现 structured concurrency 安全的值传递。
 * <p>
 * 由 WKC AI Platform 自带（vendor 自主工程 {@code wkc-common}）。
 */
public final class UserContext {

    private static final ScopedValue<Long> USER_ID = ScopedValue.newInstance();

    private UserContext() {
    }

    /** 获取当前绑定用户 ID，未绑定时返回 null。 */
    public static Long getUser() {
        return USER_ID.isBound() ? USER_ID.get() : null;
    }

    /** 在用户上下文中执行任务。 */
    public static void runWith(Long userId, Runnable task) {
        ScopedValue.where(USER_ID, userId).run(task);
    }

    /** 在用户上下文中执行任务（可抛受检异常）。 */
    public static <T> T callWith(Long userId, Callable<T> task) throws Exception {
        return ScopedValue.where(USER_ID, userId).call(task);
    }
}
