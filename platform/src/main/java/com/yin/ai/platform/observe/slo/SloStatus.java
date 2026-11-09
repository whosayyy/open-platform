package com.yin.ai.platform.observe.slo;

/**
 * SLO 评估状态 — 单个 SLO 在当前窗口内的达标情况。
 *
 * @param name             SLO 名称
 * @param currentValue     当前实际值
 * @param target           目标值
 * @param compliant        是否达标
 * @param errorBudgetUsed  已消耗的错误预算（0.0 ~ 1.0）
 * @param remainingBudget  剩余错误预算（0.0 ~ 1.0）
 */
public record SloStatus(
        String name,
        double currentValue,
        double target,
        boolean compliant,
        double errorBudgetUsed,
        double remainingBudget) {

    public boolean isBurningBudget() {
        return errorBudgetUsed > 0.1;
    }

    public boolean isCriticalBurn() {
        return errorBudgetUsed > 0.5;
    }
}
