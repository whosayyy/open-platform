package com.yin.ai.platform.memory.exception;

/**
 * Memory 异常基类 — 所有 Memory 相关异常的根类型。
 * <p>
 * 携带 {@link MemoryErrorCode} 便于统一异常处理和监控告警。
 * 遵循已有 {@code BaseBusinessException} 模式。
 */
public class MemoryException extends RuntimeException {

    private final MemoryErrorCode errorCode;

    public MemoryException(MemoryErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MemoryException(MemoryErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public MemoryErrorCode getErrorCode() {
        return errorCode;
    }
}
