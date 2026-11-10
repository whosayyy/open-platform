package com.yin.ai.platform.gateway.router.strategy;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PriorityFallbackStrategy — 优先级 fallback 路由策略")
class PriorityFallbackStrategyTest {

    private RouterContext ctx;
    private ModelDefinition primary;
    private ModelDefinition secondary;
    private ModelDefinition tertiary;
    private GatewayRequest request;

    @BeforeEach
    void setUp() {
        ctx = new RouterContext();
        request = new GatewayRequest();

        primary = ModelDefinition.builder()
                .name("deepseek-chat")
                .provider("deepseek")
                .costPer1kPromptTokens(0.00014)
                .costPer1kCompletionTokens(0.00028)
                .build();
        secondary = ModelDefinition.builder()
                .name("qwen-plus")
                .provider("dashscope")
                .costPer1kPromptTokens(0.0005)
                .costPer1kCompletionTokens(0.0015)
                .build();
        tertiary = ModelDefinition.builder()
                .name("gpt-4")
                .provider("openai")
                .costPer1kPromptTokens(0.03)
                .costPer1kCompletionTokens(0.06)
                .build();
    }

    @Test
    @DisplayName("选择优先级最高的健康模型")
    void shouldSelectHighestPriorityHealthyModel() {
        var strategy = new PriorityFallbackStrategy(List.of("deepseek-chat", "qwen-plus"));
        ModelDefinition selected = strategy.select(request, List.of(primary, secondary), ctx);
        assertThat(selected.getName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("主模型不健康时 fallback 到备用模型")
    void shouldFallbackWhenPrimaryUnhealthy() {
        var strategy = new PriorityFallbackStrategy(List.of("deepseek-chat", "qwen-plus"));
        ctx.setHealthStatus("deepseek-chat", false);
        ModelDefinition selected = strategy.select(request, List.of(primary, secondary), ctx);
        assertThat(selected.getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("所有模型不健康时返回优先级列表第一个")
    void shouldReturnFirstPriorityWhenAllUnhealthy() {
        var strategy = new PriorityFallbackStrategy(List.of("deepseek-chat", "qwen-plus"));
        ctx.setHealthStatus("deepseek-chat", false);
        ctx.setHealthStatus("qwen-plus", false);
        ModelDefinition selected = strategy.select(request, List.of(primary, secondary), ctx);
        assertThat(selected.getName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("优先级列表中的模型不存在时跳过")
    void shouldSkipNonExistentModels() {
        var strategy = new PriorityFallbackStrategy(List.of("non-existent", "qwen-plus"));
        ModelDefinition selected = strategy.select(request, List.of(primary, secondary), ctx);
        assertThat(selected.getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("策略名称为 priority-fallback")
    void shouldHaveCorrectName() {
        var strategy = new PriorityFallbackStrategy(List.of());
        assertThat(strategy.name()).isEqualTo("priority-fallback");
    }

    @Test
    @DisplayName("getPriorityOrder 返回优先级列表副本")
    void shouldReturnPriorityOrderCopy() {
        var strategy = new PriorityFallbackStrategy(List.of("a", "b", "c"));
        List<String> order = strategy.getPriorityOrder();
        order.add("d");
        assertThat(strategy.getPriorityOrder()).hasSize(3);
    }

    @Test
    @DisplayName("三模型优先级链式 fallback")
    void shouldChainFallbackThroughThreeModels() {
        var strategy = new PriorityFallbackStrategy(List.of("deepseek-chat", "qwen-plus", "gpt-4"));
        ctx.setHealthStatus("deepseek-chat", false);
        ctx.setHealthStatus("qwen-plus", false);

        ModelDefinition selected = strategy.select(request, List.of(primary, secondary, tertiary), ctx);
        assertThat(selected.getName()).isEqualTo("gpt-4");
    }
}
