package com.yin.ai.platform.memory.exception;

/**
 * 存储后端异常 — 当 Redis/MySQL/Milvus 等存储不可用时抛出。
 * 调用方应使用 circuit breaker 降级处理。
 */
public class MemoryStoreException extends MemoryException {

    public MemoryStoreException(String message) {
        super(MemoryErrorCode.STORE_UNAVAILABLE, message);
    }

    public MemoryStoreException(String message, Throwable cause) {
        super(MemoryErrorCode.STORE_UNAVAILABLE, message, cause);
    }
}
