package com.yin.ai.platform.memory.exception;

/**
 * 并发冲突异常 — 当乐观锁版本号不匹配时抛出。
 * 调用方应重试整个操作（re-read + re-apply + CAS write）。
 */
public class MemoryConcurrencyException extends MemoryException {

    public MemoryConcurrencyException(String message) {
        super(MemoryErrorCode.CONCURRENCY_CONFLICT, message);
    }

    public MemoryConcurrencyException(String message, Throwable cause) {
        super(MemoryErrorCode.CONCURRENCY_CONFLICT, message, cause);
    }
}
