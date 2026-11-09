package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.agent.pattern.ReasoningMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link DeepSeekOptionResolver} 对 Gateway 强制工具（structured output）的支持：
 * forceToolChoice → tool_choice 映射；forcedToolName → 禁用内部执行。
 */
@DisplayName("DeepSeekOptionResolver")
class DeepSeekOptionResolverTest {

    private final DeepSeekOptionResolver resolver = new DeepSeekOptionResolver();

    private static final String TOOL_NAME = "submitResult";

    private OpenAiChatOptions resolve(GatewayOptions options) {
        ChatOptions chatOptions = resolver.resolve(options);
        assertThat(chatOptions).isNotNull();
        return (OpenAiChatOptions) chatOptions;
    }

    @Test
    @DisplayName("null options 返回 null")
    void nullOptionsReturnsNull() {
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    @DisplayName("forceToolChoice + forcedToolName → tool_choice 设置为 function(name)")
    void forcedToolSetsToolChoice() {
        GatewayOptions options = new GatewayOptions();
        options.setForceToolChoice(true);
        options.setForcedToolName(TOOL_NAME);
        options.setOutputSchema(Map.of("type", "object"));

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getToolChoice()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) result.getToolChoice();
        assertThat(choice.get("type")).isEqualTo("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) choice.get("function");
        assertThat(function).containsEntry("name", TOOL_NAME);
    }

    @Test
    @DisplayName("forceToolChoice=false 时只注册工具，不强制 tool_choice")
    void noForceToolChoiceMeansNoToolChoice() {
        GatewayOptions options = new GatewayOptions();
        options.setForceToolChoice(false);
        options.setForcedToolName(TOOL_NAME);
        options.setOutputSchema(Map.of("type", "object"));

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getToolChoice()).isNull();
    }

    @Test
    @DisplayName("forcedToolName 存在时禁用内部执行（capture-only）")
    void forcedToolDisablesInternalExecution() {
        GatewayOptions options = new GatewayOptions();
        options.setForcedToolName(TOOL_NAME);
        options.setEnableToolExecution(true);

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    @DisplayName("无 forcedToolName 时内部执行跟随 options.isEnableToolExecution()")
    void nonForcedToolFollowsToolExecution() {
        GatewayOptions options = new GatewayOptions();
        options.setEnableToolExecution(true);

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getInternalToolExecutionEnabled()).isTrue();
    }

    @Test
    @DisplayName("jsonResponse → response_format = json_object")
    void jsonResponseSetsFormat() {
        GatewayOptions options = new GatewayOptions();
        options.setJsonResponse(true);

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    @DisplayName("model/temperature/maxTokens 透传")
    void basicOptionsPropagate() {
        GatewayOptions options = new GatewayOptions();
        options.setModel("deepseek-chat");
        options.setTemperature(0.1);
        options.setMaxTokens(512);

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getModel()).isEqualTo("deepseek-chat");
        assertThat(result.getTemperature()).isEqualTo(0.1);
        assertThat(result.getMaxTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("显式 ModelExecutionOptions 覆盖全局 thinking 配置")
    void explicitReasoningOptionsAreMappedPerExecution() {
        GatewayOptions options = new GatewayOptions();
        options.setForceToolChoice(true);
        options.setForcedToolName(TOOL_NAME);
        options.setModelExecutionOptions(new ModelExecutionOptions(
                ReasoningMode.HIGH, ReasoningMode.MEDIUM, "HIGH unsupported"));

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getReasoningEffort()).isEqualTo("medium");
        assertThat(result.getExtraBody()).containsKey("thinking");
        assertThat(result.getToolChoice())
                .as("DeepSeek Thinking 请求必须完全省略 tool_choice，包括 auto")
                .isNull();
    }

    @Test
    @DisplayName("Reasoning OFF 显式关闭 DeepSeek 默认 Thinking")
    void reasoningOffExplicitlyDisablesThinking() {
        GatewayOptions options = new GatewayOptions();
        options.setModelExecutionOptions(new ModelExecutionOptions(
                ReasoningMode.OFF, ReasoningMode.OFF, null));

        OpenAiChatOptions result = resolve(options);

        assertThat(result.getExtraBody()).containsEntry(
                "thinking", Map.of("type", "disabled"));
    }
}
