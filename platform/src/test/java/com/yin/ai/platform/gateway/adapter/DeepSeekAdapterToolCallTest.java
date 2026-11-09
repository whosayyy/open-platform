package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link DeepSeekAdapter} 的强制工具（structured output）链路：
 * forcedToolName+outputSchema → 注册 capture-only 工具；响应中提取 toolCallArguments。
 */
@DisplayName("DeepSeekAdapter — 强制工具 tool-call 链路")
class DeepSeekAdapterToolCallTest {

    private static final String TOOL_NAME = "submitResult";
    private static final String ARGS_JSON = "{\"result\":\"ok\"}";

    /** Adapter + fluent 链的 mock 集合：spec 暴露出来供 verify toolCallbacks。 */
    private static final class AdapterChain {
        final DeepSeekAdapter adapter;
        final ChatClient.ChatClientRequestSpec spec;

        AdapterChain(ChatResponse response) {
            ChatClient chatClient = mock(ChatClient.class);
            spec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt()).thenReturn(spec);
            when(spec.system(anyString())).thenReturn(spec);
            when(spec.messages(any(List.class))).thenReturn(spec);
            when(spec.options(any(ChatOptions.class))).thenReturn(spec);
            when(spec.toolCallbacks(any(ToolCallback[].class))).thenReturn(spec);
            when(spec.call()).thenReturn(callResponse);
            when(callResponse.chatResponse()).thenReturn(response);

            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            when(builder.build()).thenReturn(chatClient);

            @SuppressWarnings("unchecked")
            MessageConverter<Message> messageConverter = mock(MessageConverter.class);
            when(messageConverter.convert(any())).thenReturn(mock(Message.class));

            OptionResolver optionResolver = mock(OptionResolver.class);
            when(optionResolver.resolve(any())).thenReturn(OpenAiChatOptions.builder().build());

            FunctionRegistry functionRegistry = mock(FunctionRegistry.class);

            adapter = new DeepSeekAdapter(builder, messageConverter, optionResolver,
                    functionRegistry, null);
        }
    }

    private GatewayRequest forcedToolRequest() {
        GatewayRequest request = new GatewayRequest();
        request.setMessages(List.of(GatewayMessage.user("hi")));
        request.setSystemPrompt("sys");
        GatewayOptions options = new GatewayOptions();
        options.setForcedToolName(TOOL_NAME);
        options.setOutputSchema(Map.of("type", "object", "properties", Map.of()));
        request.setOptions(options);
        request.setTraceId("t1");
        return request;
    }

    @Test
    @DisplayName("forcedTool 请求：注册 capture 工具并提取 toolCallArguments")
    void forcedToolRegistersCaptureAndExtractsArgs() {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", TOOL_NAME, ARGS_JSON)))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));
        AdapterChain chain = new AdapterChain(response);

        GatewayResponse result = chain.adapter.call(forcedToolRequest());

        assertThat(result.getToolCallArguments()).isEqualTo(ARGS_JSON);

        ArgumentCaptor<ToolCallback[]> captor = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(chain.spec).toolCallbacks(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue()[0].getToolDefinition().name()).isEqualTo(TOOL_NAME);
    }

    @Test
    @DisplayName("forcedTool 请求但模型返回纯文本：toolCallArguments 为 null，content 保留")
    void forcedToolWithTextResponse() {
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("我不会调用工具").build())));
        AdapterChain chain = new AdapterChain(response);

        GatewayResponse result = chain.adapter.call(forcedToolRequest());

        assertThat(result.getToolCallArguments()).isNull();
        assertThat(result.getContent()).isEqualTo("我不会调用工具");
    }

    @Test
    @DisplayName("forcedTool 请求但工具调用名称不匹配：不提取 toolCallArguments")
    void forcedToolWithDifferentToolName() {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "otherTool", ARGS_JSON)))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));
        AdapterChain chain = new AdapterChain(response);

        GatewayResponse result = chain.adapter.call(forcedToolRequest());

        assertThat(result.getToolCallArguments()).isNull();
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("无 forcedTool 请求：不注册 capture 工具，纯文本响应")
    void noForcedToolPath() {
        GatewayRequest request = new GatewayRequest();
        request.setMessages(List.of(GatewayMessage.user("hi")));
        request.setSystemPrompt("sys");
        request.setOptions(new GatewayOptions());
        request.setTraceId("t1");

        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("hello").build())));
        AdapterChain chain = new AdapterChain(response);

        GatewayResponse result = chain.adapter.call(request);

        assertThat(result.getContent()).isEqualTo("hello");
        assertThat(result.getToolCallArguments()).isNull();
        verify(chain.spec, never()).toolCallbacks(any(ToolCallback[].class));
    }
}
