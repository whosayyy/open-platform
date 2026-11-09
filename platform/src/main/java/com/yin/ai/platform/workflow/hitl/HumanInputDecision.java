package com.yin.ai.platform.workflow.hitl;

/**
 * 人工介入决策 — Runtime 在步骤完成后据此决定是否暂停。
 */
public record HumanInputDecision(
        boolean needInput,
        String reason,
        boolean blockNext
) {

    public static HumanInputDecision proceed() {
        return new HumanInputDecision(false, null, false);
    }

    public static HumanInputDecision pause(String reason) {
        return new HumanInputDecision(true, reason, true);
    }
}
