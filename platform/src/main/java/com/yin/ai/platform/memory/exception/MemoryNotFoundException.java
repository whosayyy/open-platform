package com.yin.ai.platform.memory.exception;

/**
 * Memory 未找到异常 — 当请求的 MemoryId 在 Store 中不存在时抛出。
 */
public class MemoryNotFoundException extends MemoryException {

    public MemoryNotFoundException(String message) {
        super(MemoryErrorCode.NOT_FOUND, message);
    }

    public MemoryNotFoundException(String message, Throwable cause) {
        super(MemoryErrorCode.NOT_FOUND, message, cause);
    }
}
