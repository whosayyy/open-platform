package com.yin.ai.platform.core.memory;

/**
 * Memory 生命周期状态机 — 严格单向流转路径。
 * <pre>
 * CREATED → LOADING → ACTIVE ⇄ CONSOLIDATING
 *                  ↘     ↙
 *              RECOVERING  → COMPRESSING → ACTIVE
 *                  ↓           ↓
 *               ACTIVE     ARCHIVED → DELETED
 * </pre>
 * 只有 ACTIVE 状态可读写，ARCHIVED 和 DELETED 为终态。
 * 设计理由：复用已有 {@code TaskState} 的 CAS 状态机模式，线程安全且零锁。
 */
public enum LifecycleState {
    CREATED,
    LOADING,
    ACTIVE,
    CONSOLIDATING,
    COMPRESSING,
    ARCHIVED,
    DELETED,
    RECOVERING;

    /** 终态判断 — 到达终态后不再接受任何操作 */
    public boolean isTerminal() {
        return this == ARCHIVED || this == DELETED;
    }

    /** 只有 ACTIVE 状态允许读写 Cell */
    public boolean isMutable() {
        return this == ACTIVE;
    }

    /** 校验状态转换合法性，防止非法跳转 */
    public boolean isTransitionAllowed(LifecycleState target) {
        return switch (this) {
            case CREATED -> target == LOADING;
            case LOADING -> target == ACTIVE || target == RECOVERING;
            case ACTIVE -> target == CONSOLIDATING || target == COMPRESSING
                    || target == ARCHIVED || target == DELETED;
            case CONSOLIDATING, COMPRESSING -> target == ACTIVE;
            case RECOVERING -> target == ACTIVE;
            case ARCHIVED -> target == DELETED;
            case DELETED -> false;
        };
    }
}
