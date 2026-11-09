package com.yin.ai.platform.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Agent 生命周期状态机。
 * <p>
 * 合法转换路径:
 * <pre>
 * IDLE → PLANNING → ACTING → OBSERVING → REFLECTING
 *                    ↑     ↓                   │
 *                    │  AWAITING_APPROVAL      │
 *                    │     ↓                   │
 *                    └─────┘ (APPROVED/RETRY)  │
 *                                        │
 *                               ┌────────┼────────┐
 *                               ▼        ▼        ▼
 *                          COMPLETED  FAILED  CANCELLED  TIMEOUT
 * </pre>
 */
public enum AgentState {

    IDLE,
    PLANNING,
    ACTING,
    OBSERVING,
    REFLECTING,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT;

    private static final Set<AgentState> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED, TIMEOUT);

    private static final Set<AgentState> HITL_STATES = EnumSet.of(AWAITING_APPROVAL, APPROVED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isHitl() {
        return HITL_STATES.contains(this);
    }

    public boolean canTransitionTo(AgentState target) {
        if (this.isTerminal()) {
            return false;
        }
        return switch (this) {
            case IDLE       -> target == PLANNING;
            case PLANNING   -> target == ACTING || target == FAILED;
            case ACTING     -> target == OBSERVING || target == AWAITING_APPROVAL
                            || target == FAILED || target == CANCELLED || target == TIMEOUT;
            case OBSERVING  -> target == REFLECTING;
            case REFLECTING -> target == ACTING || target == COMPLETED || target == FAILED || target == CANCELLED;
            case AWAITING_APPROVAL -> target == APPROVED || target == REJECTED;
            case APPROVED   -> target == ACTING;
            case REJECTED   -> target == ACTING || target == REFLECTING;
            default         -> false;
        };
    }
}
