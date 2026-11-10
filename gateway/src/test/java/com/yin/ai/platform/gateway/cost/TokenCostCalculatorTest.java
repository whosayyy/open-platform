package com.yin.ai.platform.gateway.cost;

import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenCostCalculator — Token 成本计算器")
class TokenCostCalculatorTest {

    private TokenCostCalculator calculator;
    private ModelDefinition model;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        calculator = new TokenCostCalculator(props);

        model = ModelDefinition.builder()
                .name("test-model")
                .provider("test")
                .costPer1kPromptTokens(0.001)
                .costPer1kCompletionTokens(0.002)
                .build();
    }

    @Test
    @DisplayName("标准计费（无缓存）")
    void shouldCalculateStandardCost() {
        TokenUsage usage = new TokenUsage(1000, 500, 1500);
        CostResult result = calculator.calculate(model, usage);

        assertThat(result.getTotalCost()).isEqualTo(0.001 + 0.001); // $0.002
        assertThat(result.getPromptCost()).isEqualTo(0.001);
        assertThat(result.getCompletionCost()).isEqualTo(0.001);
        assertThat(result.getCacheDiscount()).isEqualTo(0);
        assertThat(result.isCacheHit()).isFalse();
    }

    @Test
    @DisplayName("缓存命中计费（cached tokens 10%）")
    void shouldDiscountCachedTokens() {
        TokenUsage usage = new TokenUsage(1000, 500, 1500);
        usage.setCachedPromptTokens(800);
        usage.setCacheHit(true);

        CostResult result = calculator.calculate(model, usage);

        int billablePrompt = 200 + (int) Math.ceil(800 * 0.1); // 200 + 80 = 280
        double expectedPromptCost = (billablePrompt / 1000.0) * 0.001;

        assertThat(result.getBillablePromptTokens()).isEqualTo(billablePrompt);
        assertThat(result.getPromptCost()).isEqualTo(expectedPromptCost);
        assertThat(result.getCacheDiscount()).isPositive();
        assertThat(result.isCacheHit()).isTrue();
    }

    @Test
    @DisplayName("缓存写入罚金（写入 110%）")
    void shouldPenalizeCacheWrite() {
        TokenUsage usage = new TokenUsage(2000, 500, 2500);
        usage.setCacheWriteTokens(1000);

        CostResult result = calculator.calculate(model, usage);

        // 1000 normal + 1000 * 1.1 = 2100 billable
        int expectedBillable = 1000 + (int) Math.ceil(1000 * 1.1);
        assertThat(result.getBillablePromptTokens()).isEqualTo(expectedBillable);
        assertThat(result.getCacheDiscount()).isNegative(); // write penalty
    }

    @Test
    @DisplayName("null TokenUsage 返回零成本")
    void shouldHandleNullUsage() {
        CostResult result = calculator.calculate(model, null);
        assertThat(result.getTotalCost()).isEqualTo(0);
        assertThat(result.getModel()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("预估成本")
    void shouldEstimateCost() {
        double cost = calculator.estimate(model, 2000, 1000);
        assertThat(cost).isEqualTo(0.002 + 0.002); // 2 * $0.001 + 1 * $0.002
    }

    @Test
    @DisplayName("仅 prompt token 计费")
    void shouldCalculatePromptOnly() {
        TokenUsage usage = new TokenUsage(500, 0, 500);
        CostResult result = calculator.calculate(model, usage);
        assertThat(result.getPromptCost()).isEqualTo(0.0005);
        assertThat(result.getCompletionCost()).isEqualTo(0);
    }
}
