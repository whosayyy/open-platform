package com.yin.ai.platform.core.memory.working;

/**
 * WorkingMemory 生命周期状态 — 通过 CAS 原子操作转换。
 * COMPLETED/FAILED 为终态，不可逆转。
 */
public enum WorkingMemoryState {
    ACTIVE,
    SUSPENDED,
    RESUMING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
