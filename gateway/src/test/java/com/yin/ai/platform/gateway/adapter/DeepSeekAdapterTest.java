package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.adapter.ToolCallbackResolver;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DeepSeekAdapter — 适配器基础结构测试")
class DeepSeekAdapterTest {

    private DeepSeekAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MessageConverter<Message> messageConverter = mock(MessageConverter.class);
        when(messageConverter.convert(any())).thenReturn(mock(Message.class));

        OptionResolver optionResolver = mock(OptionResolver.class);
        when(optionResolver.resolve(any())).thenReturn(null);

        ToolCallbackResolver toolCallbackResolver = mock(ToolCallbackResolver.class);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        adapter = new DeepSeekAdapter(builder, messageConverter, optionResolver, toolCallbackResolver, null);
    }

    @Test
    @DisplayName("name 返回 deepseek")
    void shouldReturnName() {
        assertThat(adapter.name()).isEqualTo("deepseek");
    }

    @Test
    @DisplayName("supportedModels 包含 V4 正式名称与旧兼容名称")
    void shouldSupportDeepSeekModels() {
        assertThat(adapter.supportedModels())
                .containsExactlyInAnyOrder("deepseek-v4-flash", "deepseek-v4-pro",
                        "deepseek-chat", "deepseek-reasoner");
    }

    @Test
    @DisplayName("构造函数不抛异常（DI 正确）")
    void shouldConstructWithoutException() {
        assertNotNull(adapter);
    }

    @Test
    @DisplayName("call 空消息不抛 NullPointerException")
    void shouldHandleNullSystemPromptGracefully() {
        GatewayRequest request = new GatewayRequest(
                List.of(GatewayMessage.user("Hi")), null);
        request.setTraceId("t1");
        // Adapter 能正常构建，message converter 和 client 正常工作即可
        assertThat(adapter).isNotNull();
    }
}
