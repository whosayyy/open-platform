package com.yin.ai.platform.gateway.cache;

import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheFilter")
class CacheFilterTest {

    private CacheFilter filter;
    private boolean chainCalled;

    @BeforeEach
    void setUp() {
        chainCalled = false;
    }

    @Test
    @DisplayName("name 返回 cache，order 返回 8")
    void shouldHaveCorrectMetadata() {
        filter = new CacheFilter(null);
        assertThat(filter.name()).isEqualTo("cache");
        assertThat(filter.order()).isEqualTo(8);
    }

    @Test
    @DisplayName("无用户消息时直接放行到 chain")
    void shouldProceedWhenNoMessages() {
        filter = new CacheFilter(null); // null manager → search returns null

        GatewayRequest request = new GatewayRequest();
        request.setTraceId("trace-1");

        GatewayResponse response = filter.filter(request, r -> {
            chainCalled = true;
            return new GatewayResponse("ok");
        });

        assertThat(chainCalled).isTrue();
        assertThat(response.getContent()).isEqualTo("ok");
    }

    @Test
    @DisplayName("无消息列表时直接放行")
    void shouldProceedWhenNullMessages() {
        filter = new CacheFilter(null);

        GatewayRequest request = new GatewayRequest();
        request.setTraceId("trace-2");

        GatewayResponse response = filter.filter(request, r -> {
            chainCalled = true;
            return new GatewayResponse("ok");
        });

        assertThat(chainCalled).isTrue();
    }

    @Test
    @DisplayName("manager 为 null 时优雅降级放行")
    void shouldDegradeWhenManagerNull() {
        filter = new CacheFilter(null);

        GatewayRequest request = new GatewayRequest();
        request.setMessages(List.of(GatewayMessage.user("hello")));
        request.setTraceId("trace-3");

        GatewayResponse response = filter.filter(request, r -> {
            chainCalled = true;
            return new GatewayResponse("llm");
        });

        assertThat(chainCalled).isTrue();
    }
}
