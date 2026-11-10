package com.yin.ai.platform.gateway.cost;

/**
 * 成本预算超限异常 — 当请求累计成本超过预算上限时抛出。
 * <p>
 * 调用方可捕获此异常进行降级处理（如返回缓存结果、切换更便宜模型等）。
 */
public class CostLimitExceededException extends RuntimeException {

    private final double currentCost;
    private final double budgetLimit;
    private final String budgetType;

    public CostLimitExceededException(double currentCost, double budgetLimit, String budgetType) {
        super(String.format("Cost limit exceeded: $%.6f / $%.6f (budget: %s)",
                currentCost, budgetLimit, budgetType));
        this.currentCost = currentCost;
        this.budgetLimit = budgetLimit;
        this.budgetType = budgetType;
    }

    public double getCurrentCost() { return currentCost; }
    public double getBudgetLimit() { return budgetLimit; }
    public String getBudgetType() { return budgetType; }
}
