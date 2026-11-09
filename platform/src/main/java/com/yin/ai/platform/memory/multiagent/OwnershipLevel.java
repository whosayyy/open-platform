package com.yin.ai.platform.memory.multiagent;

/**
 * 所有权级别 — 控制 Multi-Agent 场景下的 Memory 访问权限。
 * <p>
 * 一致性保障：CAS 乐观锁 (MemoryCell.version)，无死锁。
 * 隔离模型：agentId + OwnershipLevel 双重控制。
 */
public enum OwnershipLevel {
    /** 独占 — 仅 owner agent 可读写 */
    EXCLUSIVE,
    /** 共享读 — owner 可写，其他授权 agent 可读 */
    SHARED_READ,
    /** 共享写 — 所有授权 agent 可读写（CAS 防冲突） */
    SHARED_WRITE,
    /** 公开 — 任意 agent 可读写 */
    PUBLIC
}
