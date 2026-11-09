package com.yin.ai.platform.memory.exception;

/**
 * Memory 错误码枚举 — 覆盖所有 Memory 操作可能出现的异常场景。
 * 遵循已有 {@code SystemErrorCode} 模式，每个错误码对应一个异常子类。
 */
public enum MemoryErrorCode {
    NOT_FOUND,
    STORE_UNAVAILABLE,
    CIRCUIT_BREAKER_OPEN,
    INVALID_STATE_TRANSITION,
    POLICY_VIOLATION,
    CONSOLIDATION_FAILED,
    COMPRESSION_FAILED,
    CHECKPOINT_CREATE_FAILED,
    CHECKPOINT_CORRUPTED,
    RECOVERY_FAILED,
    SECURITY_VIOLATION,
    VERSION_MISMATCH,
    MIGRATION_FAILED,
    SIZE_LIMIT_EXCEEDED,
    CONCURRENCY_CONFLICT
}
