package com.yin.ai.platform.gateway.cost;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.filter.CostFilter;
import com.yin.ai.platform.gateway.filter.FilterChain;
import com.yin.ai.platform.gateway.stream.FinishPacket;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import com.yin.ai.platform.gateway.model.DefaultModelRegistry;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CostFilter — 成本过滤器")
class CostFilterTest {

    private CostFilter filter;
    private ModelRegistry registry;
    private GatewayRequest request;

    @BeforeEach
    void setUp() {
        ModelDefinition model = ModelDefinition.builder()
                .name("test-model")
                .provider("test")
                .costPer1kPromptTokens(0.001)
                .costPer1kCompletionTokens(0.002)
                .maxTokens(4096)
                .build();

        registry = new DefaultModelRegistry(List.of(model), "test-model");

        GatewayProperties props = new GatewayProperties();
        TokenCostCalculator calculator = new TokenCostCalculator(props);
        CostLimiter limiter = new CostLimiter(0.50, 5.0, 50.0);

        filter = new CostFilter(calculator, limiter, registry);
        request = new GatewayRequest();
        request.setCaller("test-caller");
    }

    @Test
    @DisplayName("响应包含 TokenUsage 时记录成本")
    void shouldRecordCostWhenTokenUsagePresent() {
        FilterChain chain = r -> {
            GatewayResponse resp = new GatewayResponse("ok");
            resp.setModel("test-model");
            resp.setTokenUsage(new TokenUsage(1000, 500, 1500));
            return resp;
        };

        GatewayResponse response = filter.filter(request, chain);
        assertThat(response.getContent()).isEqualTo("ok");
    }

    @Test
    @DisplayName("响应无 TokenUsage 时正常返回")
    void shouldPassThroughWithoutTokenUsage() {
        FilterChain chain = r -> new GatewayResponse("ok");

        GatewayResponse response = filter.filter(request, chain);
        assertThat(response.getContent()).isEqualTo("ok");
    }

    @Test
    @DisplayName("filter 名称为 cost")
    void shouldHaveCorrectName() {
        assertThat(filter.name()).isEqualTo("cost");
    }

    @Test
    @DisplayName("order 为 45")
    void shouldHaveCorrectOrder() {
        assertThat(filter.order()).isEqualTo(45);
    }

    @Test
    @DisplayName("请求预算超限时异常仍正确传播")
    void shouldPropagateRequestOverBudget() {
        CostLimiter strictLimiter = new CostLimiter(0.0000001, 0, 0);
        GatewayProperties props = new GatewayProperties();
        TokenCostCalculator calc = new TokenCostCalculator(props);
        ModelDefinition model = ModelDefinition.builder()
                .name("expensive")
                .provider("test")
                .costPer1kPromptTokens(1.0)
                .costPer1kCompletionTokens(1.0)
                .maxTokens(4096)
                .build();
        ModelRegistry strictRegistry = new DefaultModelRegistry(List.of(model), "expensive");
        CostFilter strictFilter = new CostFilter(calc, strictLimiter, strictRegistry);

        FilterChain chain = r -> new GatewayResponse("ok");

        try {
            strictFilter.filter(request, chain);
        } catch (CostLimitExceededException e) {
            assertThat(e).hasMessageContaining("per-request");
        }
    }

    @Test
    @DisplayName("null model 时正常通过")
    void shouldPassWhenModelUnknown() {
        ModelDefinition dummy = ModelDefinition.builder()
                .name("dummy").provider("test")
                .costPer1kPromptTokens(0.001).costPer1kCompletionTokens(0.002)
                .build();
        CostFilter emptyFilter = new CostFilter(
                new TokenCostCalculator(new GatewayProperties()),
                new CostLimiter(0, 0, 0),
                new DefaultModelRegistry(List.of(dummy), "dummy"));
        FilterChain chain = r -> new GatewayResponse("ok");
        GatewayResponse response = emptyFilter.filter(request, chain);
        assertThat(response.getContent()).isEqualTo("ok");
    }

    @Test
    @DisplayName("成功调用写入隐私安全的日报事件")
    void shouldRecordDailyUsageEvent() {
        AtomicReference<CostUsageEvent> captured = new AtomicReference<>();
        CostFilter reportingFilter = new CostFilter(
                new TokenCostCalculator(new GatewayProperties()),
                new CostLimiter(0.50, 5.0, 50.0), registry, captured::set);
        request.setTenantId("tenant-a");
        GatewayResponse response = new GatewayResponse("ok");
        response.setModel("test-model");
        response.setTokenUsage(new TokenUsage(10, 5, 15));

        reportingFilter.filter(request, ignored -> response);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().tenantId()).isEqualTo("tenant-a");
        assertThat(captured.get().skill()).isEqualTo("test-caller");
        assertThat(captured.get().model()).isEqualTo("test-model");
        assertThat(captured.get().error()).isFalse();
    }

    @Test
    @DisplayName("流式结束包写入实际 Token 和成本")
    void shouldRecordStreamingFinishUsage() {
        AtomicReference<CostUsageEvent> captured = new AtomicReference<>();
        CostFilter reportingFilter = new CostFilter(
                new TokenCostCalculator(new GatewayProperties()),
                new CostLimiter(0.50, 5.0, 50.0), registry, captured::set);
        StreamPacket finishPacket = new StreamPacket();
        finishPacket.setType("finish");
        finishPacket.setContent(new FinishPacket(
                new TokenUsage(10, 5, 15), "test-model", "stop", 20, "trace"));

        reportingFilter.filterStream(request, ignored -> reactor.core.publisher.Flux.just(finishPacket))
                .collectList().block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().tokenUsage().getTotalTokens()).isEqualTo(15);
        assertThat(captured.get().costUsd()).isPositive();
    }
}
