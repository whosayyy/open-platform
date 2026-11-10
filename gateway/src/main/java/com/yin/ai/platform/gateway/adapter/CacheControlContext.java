package com.yin.ai.platform.gateway.adapter;

/**
 * ThreadLocal context for communicating cache_control intent
 * from the {@link CacheControlAdvisor} to the HTTP request interceptor.
 */
public final class CacheControlContext {

    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<>();

    private CacheControlContext() {}

    public static void set(boolean value) {
        ENABLED.set(value);
    }

    public static boolean isEnabled() {
        return Boolean.TRUE.equals(ENABLED.get());
    }

    public static void clear() {
        ENABLED.remove();
    }
}
