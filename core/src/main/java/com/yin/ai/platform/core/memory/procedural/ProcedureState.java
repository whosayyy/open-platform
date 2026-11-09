package com.yin.ai.platform.core.memory.procedural;

/**
 * Procedure 生命周期状态机。
 * <p>
 * RECORDING → VERIFIED → ACTIVE → DEPRECATED → RETIRED
 * <p>
 * 状态转换规则：
 * <ul>
 *   <li>RECORDING → VERIFIED：人工审核或自动验证通过</li>
 *   <li>VERIFIED → ACTIVE：首次匹配使用成功</li>
 *   <li>ACTIVE → DEPRECATED：成功率低于阈值</li>
 *   <li>任意非终态 → RETIRED：手动废弃</li>
 * </ul>
 */
public enum ProcedureState {

    /** 录制中 — 从 Task 学习阶段，尚未验证 */
    RECORDING,
    /** 已验证 — 通过人工或自动验证，等待首次使用 */
    VERIFIED,
    /** 活跃 — 可被 ProcedureMatcher 匹配复用 */
    ACTIVE,
    /** 已弃用 — 成功率不达标，不再推荐但保留记录 */
    DEPRECATED,
    /** 已退役 — 手动废弃，不参与匹配 */
    RETIRED;

    public boolean isTerminal() {
        return this == RETIRED;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean canTransitionTo(ProcedureState target) {
        return switch (this) {
            case RECORDING -> target == VERIFIED || target == RETIRED;
            case VERIFIED -> target == ACTIVE || target == RETIRED;
            case ACTIVE -> target == DEPRECATED || target == RETIRED;
            case DEPRECATED -> target == ACTIVE || target == RETIRED;
            case RETIRED -> false;
        };
    }
}
