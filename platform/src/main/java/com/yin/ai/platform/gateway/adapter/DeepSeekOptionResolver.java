package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.agent.pattern.ReasoningMode;
import com.yin.ai.platform.structured.StructuredToolGenerator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.openai.api.ResponseFormat.Type;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DeepSeekOptionResolver implements OptionResolver {

    @Override
    public String adapterName() {
        return "deepseek";
    }

    @Override
    public ChatOptions resolve(GatewayOptions options) {
        if (options == null) {
            return null;
        }

        ReasoningMode effectiveReasoning = options.getModelExecutionOptions() != null
                ? options.getModelExecutionOptions().effectiveReasoning() : ReasoningMode.OFF;
        boolean thinking = effectiveReasoning != ReasoningMode.OFF;

        var builder = OpenAiChatOptions.builder();
        if (options.getModel() != null) {
            builder.model(options.getModel());
        }
        if (!thinking && options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }
        if (options.isJsonResponse()) {
            builder.responseFormat(ResponseFormat.builder().type(Type.JSON_OBJECT).build());
        }

        // DeepSeek 思考模式：reasoning_effort + extra_body["thinking"]（官方参数）。
        // V4 Thinking 可以携带 tools，但会拒绝整个 tool_choice 参数（包括 "auto"）；
        // 因此 Thinking 下必须完全省略该字段，服务端在存在 tools 时会默认自主选择工具。
        if (thinking) {
            String effort = effectiveReasoning.name().toLowerCase(java.util.Locale.ROOT);
            if (effort != null && !effort.isBlank()) {
                builder.reasoningEffort(effort);
            }
            builder.extraBody(Map.of("thinking", Map.of("type", "enabled")));
        } else {
            // DeepSeek V4 默认开启 Thinking。显式 OFF 必须发送 disabled，避免被全局默认选项重新开启。
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        if (!thinking && options.isForceToolChoice() && options.getForcedToolName() != null
                && !options.getForcedToolName().isBlank()) {
            builder.toolChoice(StructuredToolGenerator.functionChoice(options.getForcedToolName()));
        }
        ChatOptions chatOptions = builder.build();
        if (chatOptions instanceof ToolCallingChatOptions tc) {
            // 强制工具 = capture-only：Spring AI 不得内部执行工具、不追加第二轮对话
            boolean forced = options.getForcedToolName() != null && !options.getForcedToolName().isBlank();
            tc.setInternalToolExecutionEnabled(forced ? false : options.isEnableToolExecution());
        }
        return chatOptions;
    }

}
