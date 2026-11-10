package com.yin.ai.platform.gateway.router;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.model.DefaultModelRegistry;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.router.strategy.LowestCostStrategy;
import com.yin.ai.platform.gateway.router.strategy.PriorityFallbackStrategy;
import com.yin.ai.platform.gateway.router.strategy.RouterContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultModelRouter — 模型路由器")
class DefaultModelRouterTest {

    private ModelRegistry registry;
    private ModelDefinition deepseekChat;
    private ModelDefinition qwenPlus;
    private DefaultModelRouter router;

    @BeforeEach
    void setUp() {
        deepseekChat = ModelDefinition.builder()
                .name("deepseek-chat")
                .provider("deepseek")
                .costPer1kPromptTokens(0.00014)
                .costPer1kCompletionTokens(0.00028)
                .enabled(true)
                .build();

        qwenPlus = ModelDefinition.builder()
                .name("qwen-plus")
                .provider("dashscope")
                .costPer1kPromptTokens(0.001)
                .costPer1kCompletionTokens(0.003)
                .enabled(true)
                .build();

        registry = new DefaultModelRegistry(List.of(deepseekChat, qwenPlus), "deepseek-chat");
    }

    @Test
    @DisplayName("默认使用注入的策略")
    void shouldUseInitialStrategy() {
        router = new DefaultModelRouter(registry, new LowestCostStrategy(), new RouterContext());
        GatewayRequest request = new GatewayRequest();
        ModelDefinition selected = router.route(request);
        assertThat(selected.getName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("显式指定模型名时跳过策略")
    void shouldUseExplicitModelOverStrategy() {
        router = new DefaultModelRouter(registry, new LowestCostStrategy(), new RouterContext());
        GatewayOptions options = new GatewayOptions();
        options.setModel("qwen-plus");
        GatewayRequest request = new GatewayRequest();
        request.setOptions(options);

        ModelDefinition selected = router.route(request);
        assertThat(selected.getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("显式指定不存在的模型时抛出异常")
    void shouldThrowOnUnknownExplicitModel() {
        router = new DefaultModelRouter(registry, new LowestCostStrategy(), new RouterContext());
        GatewayOptions options = new GatewayOptions();
        options.setModel("non-existent");
        GatewayRequest request = new GatewayRequest();
        request.setOptions(options);

        assertThatThrownBy(() -> router.route(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-existent");
    }

    @Test
    @DisplayName("动态切换策略")
    void shouldSwitchStrategy() {
        router = new DefaultModelRouter(registry, new LowestCostStrategy(), new RouterContext());
        router.setStrategy(new PriorityFallbackStrategy(List.of("qwen-plus", "deepseek-chat")));

        GatewayRequest request = new GatewayRequest();
        ModelDefinition selected = router.route(request);
        assertThat(selected.getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("获取当前策略")
    void shouldReturnCurrentStrategy() {
        router = new DefaultModelRouter(registry, new LowestCostStrategy(), new RouterContext());
        assertThat(router.getCurrentStrategy().name()).isEqualTo("lowest-cost");
    }

    @Test
    @DisplayName("getRouterContext 返回路由上下文")
    void shouldReturnRouterContext() {
        router = new DefaultModelRouter(registry, new LowestCostStrategy(), new RouterContext());
        assertThat(router.getRouterContext()).isNotNull();
    }
}
