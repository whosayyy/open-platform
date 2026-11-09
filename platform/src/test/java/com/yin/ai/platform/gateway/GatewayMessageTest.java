package com.yin.ai.platform.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayMessage")
class GatewayMessageTest {

    @Test
    @DisplayName("system(String, boolean) 创建带 cacheControl 标记的消息")
    void systemWithCacheControl() {
        GatewayMessage msg = GatewayMessage.system("System prompt", true);

        assertThat(msg.getRole()).isEqualTo(GatewayMessage.Role.system);
        assertThat(msg.getContent()).isEqualTo("System prompt");
        assertThat(msg.isCacheControl()).isTrue();
    }

    @Test
    @DisplayName("system(String) 创建不带 cacheControl 标记的消息")
    void systemWithoutCacheControl() {
        GatewayMessage msg = GatewayMessage.system("System prompt");

        assertThat(msg.getRole()).isEqualTo(GatewayMessage.Role.system);
        assertThat(msg.isCacheControl()).isFalse();
    }

    @Test
    @DisplayName("user(String) 创建用户消息，cacheControl 默认 false")
    void userMessage() {
        GatewayMessage msg = GatewayMessage.user("Hello");

        assertThat(msg.getRole()).isEqualTo(GatewayMessage.Role.user);
        assertThat(msg.isCacheControl()).isFalse();
    }

    @Test
    @DisplayName("setCacheControl 可切换标记")
    void cacheControlSetter() {
        GatewayMessage msg = new GatewayMessage();
        assertThat(msg.isCacheControl()).isFalse();

        msg.setCacheControl(true);
        assertThat(msg.isCacheControl()).isTrue();

        msg.setCacheControl(false);
        assertThat(msg.isCacheControl()).isFalse();
    }
}
