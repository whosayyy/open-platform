package com.yin.ai.platform.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Provider-level helpers retained for the Gateway adapter. Application code must use
 * {@link StructuredClient}; this class deliberately has no ChatClient dependency.
 */
public final class StructuredToolGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile boolean thinkingModeEnabled;

    private StructuredToolGenerator() { }

    public static void setThinkingModeEnabled(boolean enabled) {
        thinkingModeEnabled = enabled;
    }

    public static boolean isThinkingModeEnabled() {
        return thinkingModeEnabled;
    }

    public static boolean isThinkingModeToolChoiceRejection(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("tool_choice") && normalized.contains("thinking")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static ToolCallback captureTool(String toolName, String toolDescription,
                                           Map<String, Object> schema) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(toolName)
                .description(toolDescription)
                .inputSchema(toJson(schema))
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"accepted\":true}";
            }
        };
    }

    public static Optional<String> extractToolCallArguments(ChatResponse response, String toolName) {
        if (response == null || response.getResult() == null || toolName == null) {
            return Optional.empty();
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output == null || !output.hasToolCalls()) {
            return Optional.empty();
        }
        return output.getToolCalls().stream()
                .filter(call -> call != null && toolName.equals(call.name()))
                .map(AssistantMessage.ToolCall::arguments)
                .filter(arguments -> arguments != null && !arguments.isBlank())
                .findFirst();
    }

    public static Map<String, Object> functionChoice(String toolName) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toolName);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "function");
        choice.put("function", function);
        return choice;
    }

    private static String toJson(Map<String, Object> schema) {
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize tool schema", error);
        }
    }
}
