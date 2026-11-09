package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelToolCall;
import com.yin.ai.platform.core.model.ModelUsage;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider-neutral reason/action/observation loop backed by core model and tool ports. */
public final class ReActAgentPattern implements AgentPattern {

    private final int maxIterations;

    public ReActAgentPattern(int maxIterations) {
        this.maxIterations = Math.max(1, maxIterations);
    }

    @Override
    public AgentMode mode() {
        return AgentMode.REACT;
    }

    @Override
    public PatternResult execute(PatternContext context) {
        List<ModelMessage> messages = new ArrayList<>(context.initialMessages());
        ModelUsage totalUsage = ModelUsage.empty();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            context.emit("react.iteration.started", Map.of("iteration", iteration));
            ModelResponse response = context.callModel(messages, context.availableTools(),
                    Map.of("agentMode", mode().name(), "iteration", iteration));
            totalUsage = add(totalUsage, response.usage());
            if (response.toolCalls().isEmpty()) {
                Map<String, Object> metadata = new LinkedHashMap<>(response.metadata());
                metadata.put("mode", mode().name());
                metadata.put("iterations", iteration);
                metadata.put("usage", totalUsage);
                return new PatternResult(response.content(), metadata);
            }

            messages.add(new ModelMessage(com.yin.ai.platform.core.model.ModelRole.ASSISTANT,
                    response.content(), null, Map.of("toolCalls", response.toolCalls())));
            for (ModelToolCall call : response.toolCalls()) {
                executeTool(call, messages, context);
            }
        }
        throw new IllegalStateException("ReAct exceeded maximum iterations: " + maxIterations);
    }

    private static void executeTool(ModelToolCall call, List<ModelMessage> messages,
                                    PatternContext context) {
        context.emit("tool.started", Map.of("tool", call.name(),
                "toolCallId", safe(call.id())));
        Tool tool = context.toolRegistry().find(call.name()).orElse(null);
        ToolResult result;
        if (tool == null) {
            result = ToolResult.failure("TOOL_NOT_FOUND", "Unknown tool: " + call.name());
        } else {
            try {
                result = tool.execute(new ToolInput(call.arguments(), call.id(),
                        context.executionContext()));
                if (result == null) {
                    result = ToolResult.failure("EMPTY_RESULT", "Tool returned no result");
                }
            } catch (RuntimeException exception) {
                result = ToolResult.failure("TOOL_EXECUTION_FAILED", exception.getMessage());
            }
        }
        String observation = result.success() ? stringify(result.data())
                : "Tool error [" + safe(result.errorCode()) + "]: "
                + safe(result.errorMessage());
        messages.add(ModelMessage.tool(observation, call.name(), call.id()));
        context.emit(result.success() ? "tool.completed" : "tool.failed",
                Map.of("tool", call.name(), "toolCallId", safe(call.id()),
                        "observation", observation));
    }

    private static ModelUsage add(ModelUsage left, ModelUsage right) {
        return new ModelUsage(left.inputTokens() + right.inputTokens(),
                left.outputTokens() + right.outputTokens(),
                left.totalTokens() + right.totalTokens());
    }

    private static String stringify(Object value) {
        return value == null ? "" : value instanceof String text ? text : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
