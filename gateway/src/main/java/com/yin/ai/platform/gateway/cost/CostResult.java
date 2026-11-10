package com.yin.ai.platform.gateway.cost;

/**
 * 单次请求的 Token 成本计算结果，包含明细分解。
 */
public class CostResult {

    /** 实际计费的 prompt tokens */
    private final int billablePromptTokens;
    /** 实际计费的 completion tokens */
    private final int billableCompletionTokens;
    /** prompt 成本（USD） */
    private final double promptCost;
    /** completion 成本（USD） */
    private final double completionCost;
    /** 缓存命中节省的成本（USD） */
    private final double cacheDiscount;
    /** 总成本（USD） */
    private final double totalCost;
    /** 使用的模型 */
    private final String model;
    /** 是否命中缓存 */
    private final boolean cacheHit;

    private CostResult(Builder builder) {
        this.billablePromptTokens = builder.billablePromptTokens;
        this.billableCompletionTokens = builder.billableCompletionTokens;
        this.promptCost = builder.promptCost;
        this.completionCost = builder.completionCost;
        this.cacheDiscount = builder.cacheDiscount;
        this.totalCost = builder.totalCost;
        this.model = builder.model;
        this.cacheHit = builder.cacheHit;
    }

    public int getBillablePromptTokens() { return billablePromptTokens; }
    public int getBillableCompletionTokens() { return billableCompletionTokens; }
    public double getPromptCost() { return promptCost; }
    public double getCompletionCost() { return completionCost; }
    public double getCacheDiscount() { return cacheDiscount; }
    public double getTotalCost() { return totalCost; }
    public String getModel() { return model; }
    public boolean isCacheHit() { return cacheHit; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int billablePromptTokens;
        private int billableCompletionTokens;
        private double promptCost;
        private double completionCost;
        private double cacheDiscount;
        private double totalCost;
        private String model;
        private boolean cacheHit;

        public Builder billablePromptTokens(int v) { this.billablePromptTokens = v; return this; }
        public Builder billableCompletionTokens(int v) { this.billableCompletionTokens = v; return this; }
        public Builder promptCost(double v) { this.promptCost = v; return this; }
        public Builder completionCost(double v) { this.completionCost = v; return this; }
        public Builder cacheDiscount(double v) { this.cacheDiscount = v; return this; }
        public Builder totalCost(double v) { this.totalCost = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder cacheHit(boolean v) { this.cacheHit = v; return this; }

        public CostResult build() { return new CostResult(this); }
    }

    @Override
    public String toString() {
        return String.format("Cost{model=%s, total=$%.6f, prompt=$%.6f(%d tok), completion=$%.6f(%d tok)%s}",
                model, totalCost, promptCost, billablePromptTokens, completionCost, billableCompletionTokens,
                cacheHit ? ", cached" : "");
    }
}
