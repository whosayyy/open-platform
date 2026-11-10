package com.yin.ai.platform.gateway.router.strategy;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LowestCostStrategy — 最低成本路由策略")
class LowestCostStrategyTest {

    private LowestCostStrategy strategy;
    private RouterContext ctx;
    private ModelDefinition cheap;
    private ModelDefinition expensive;
    private GatewayRequest request;

    @BeforeEach
    void setUp() {
        strategy = new LowestCostStrategy();
        ctx = new RouterContext();
        request = new GatewayRequest();

        cheap = ModelDefinition.builder()
                .name("cheap-model")
                .provider("test")
                .costPer1kPromptTokens(0.0001)
                .costPer1kCompletionTokens(0.0002)
                .enabled(true)
                .build();

        expensive = ModelDefinition.builder()
                .name("expensive-model")
                .provider("test")
                .costPer1kPromptTokens(0.01)
                .costPer1kCompletionTokens(0.03)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("选择成本最低的模型")
    void shouldSelectCheapestModel() {
        ModelDefinition selected = strategy.select(request, List.of(cheap, expensive), ctx);
        assertThat(selected.getName()).isEqualTo("cheap-model");
    }

    @Test
    @DisplayName("单候选直接返回")
    void shouldReturnSingleCandidate() {
        ModelDefinition selected = strategy.select(request, List.of(cheap), ctx);
        assertThat(selected.getName()).isEqualTo("cheap-model");
    }

    @Test
    @DisplayName("排除不健康的模型")
    void shouldExcludeUnhealthyModel() {
        ctx.setHealthStatus("cheap-model", false);
        ModelDefinition selected = strategy.select(request, List.of(cheap, expensive), ctx);
        assertThat(selected.getName()).isEqualTo("expensive-model");
    }

    @Test
    @DisplayName("所有模型不健康时仍返回成本最低的")
    void shouldFallbackWhenAllUnhealthy() {
        ctx.setHealthStatus("cheap-model", false);
        ctx.setHealthStatus("expensive-model", false);
        ModelDefinition selected = strategy.select(request, List.of(cheap, expensive), ctx);
        assertThat(selected.getName()).isEqualTo("cheap-model");
    }

    @Test
    @DisplayName("成本相同时按名称排序")
    void shouldSortByNameWhenCostTied() {
        ModelDefinition modelA = ModelDefinition.builder().name("a-model").provider("test")
                .costPer1kPromptTokens(0.001).costPer1kCompletionTokens(0.002).build();
        ModelDefinition modelB = ModelDefinition.builder().name("b-model").provider("test")
                .costPer1kPromptTokens(0.001).costPer1kCompletionTokens(0.002).build();

        ModelDefinition selected = strategy.select(request, List.of(modelB, modelA), ctx);
        assertThat(selected.getName()).isEqualTo("a-model");
    }

    @Test
    @DisplayName("空候选列表抛出异常")
    void shouldThrowOnEmptyCandidates() {
        assertThatThrownBy(() -> strategy.select(request, List.of(), ctx))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("策略名称为 lowest-cost")
    void shouldHaveCorrectName() {
        assertThat(strategy.name()).isEqualTo("lowest-cost");
    }
}
