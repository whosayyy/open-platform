package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.engine.stream.StreamPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelAdapter 契约测试")
class ModelAdapterContractTest {

    private DummyAdapter adapter;
    private GatewayRequest request;

    @BeforeEach
    void setUp() {
        GatewayResponse response = new GatewayResponse("hello");
        TokenUsage usage = new TokenUsage(100, 50, 150);
        response.setTokenUsage(usage);
        response.setModel("dummy-model");

        adapter = DummyAdapter.builder()
                .name("dummy")
                .supportedModels(Set.of("dummy-model", "dummy-model-v2"))
                .cannedResponse(response)
                .cannedStream(Flux.just(
                        StreamPacket.builder().type("text").content("hello").seq(0).build(),
                        StreamPacket.builder().type("text").content(" world").seq(1).build()
                ))
                .build();

        request = new GatewayRequest(
                List.of(GatewayMessage.user("test")),
                null
        );
        request.setTraceId("trace-001");
    }

    @Test
    @DisplayName("name 返回适配器标识")
    void shouldReturnName() {
        assertThat(adapter.name()).isEqualTo("dummy");
    }

    @Test
    @DisplayName("supportedModels 返回支持的模型列表")
    void shouldReturnSupportedModels() {
        assertThat(adapter.supportedModels()).containsExactlyInAnyOrder("dummy-model", "dummy-model-v2");
    }

    @Test
    @DisplayName("call 返回预设响应")
    void shouldReturnCannedResponse() {
        GatewayResponse resp = adapter.call(request);
        assertThat(resp.getContent()).isEqualTo("hello");
        assertThat(resp.getModel()).isEqualTo("dummy-model");
        assertThat(resp.getTokenUsage()).isNotNull();
        assertThat(resp.getTokenUsage().getTotalTokens()).isEqualTo(150);
    }

    @Test
    @DisplayName("call 记录调用次数和请求")
    void shouldRecordCalls() {
        adapter.call(request);
        adapter.call(request);
        assertThat(adapter.getCallCount()).isEqualTo(2);
        assertThat(adapter.getLastRequest().getTraceId()).isEqualTo("trace-001");
    }

    @Test
    @DisplayName("call 模拟错误时抛出异常")
    void shouldThrowOnSimulatedError() {
        DummyAdapter errorAdapter = DummyAdapter.builder()
                .name("error")
                .simulatedError("connection refused")
                .build();

        assertThatThrownBy(() -> errorAdapter.call(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    @DisplayName("stream 返回预设流")
    void shouldReturnCannedStream() {
        StepVerifier.create(adapter.stream(request))
                .expectNextMatches(p -> "text".equals(p.getType()) && "hello".equals(p.getContent()))
                .expectNextMatches(p -> "text".equals(p.getType()) && " world".equals(p.getContent()))
                .verifyComplete();
    }

    @Test
    @DisplayName("stream 模拟错误时返回 error Flux")
    void shouldReturnErrorFlux() {
        DummyAdapter errorAdapter = DummyAdapter.builder()
                .name("error")
                .simulatedError("timeout")
                .build();

        StepVerifier.create(errorAdapter.stream(request))
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().contains("timeout"))
                .verify();
    }

    @Test
    @DisplayName("默认响应不为 null 时 call 不抛异常")
    void shouldNotThrowWithDefaultResponse() {
        DummyAdapter defaultAdapter = DummyAdapter.builder().name("minimal").build();
        GatewayResponse resp = defaultAdapter.call(request);
        assertThat(resp).isNotNull();
        assertThat(resp.getContent()).isEmpty();
    }

    @Test
    @DisplayName("reset 清除调用记录")
    void shouldResetCallTracking() {
        adapter.call(request);
        adapter.reset();
        assertThat(adapter.getCallCount()).isEqualTo(0);
        assertThat(adapter.getLastRequest()).isNull();
    }
}
