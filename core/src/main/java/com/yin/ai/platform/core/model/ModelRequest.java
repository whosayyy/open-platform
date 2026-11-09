package com.yin.ai.platform.core.model;

import com.yin.ai.platform.core.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Complete request sent through the model SPI.
 *
 * @param messages        conversation messages
 * @param options         provider-neutral options
 * @param allowedTools    allowed tool <b>names</b>; derived from {@code toolDefinitions}
 *                        when that field is supplied
 * @param toolDefinitions full tool definitions, including the input schema the model needs
 *                        in order to call a tool correctly. Model adapters should prefer
 *                        this field and fall back to {@code allowedTools} only when it is
 *                        empty (e.g. a hand-built request that only knows names).
 * @param metadata        free-form orchestration metadata
 */
public record ModelRequest(
        List<ModelMessage> messages,
        ModelOptions options,
        List<String> allowedTools,
        List<ToolDefinition> toolDefinitions,
        Map<String, Object> metadata
) {
    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        options = options == null ? ModelOptions.defaults() : options;
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        if (allowedTools == null || allowedTools.isEmpty() && !toolDefinitions.isEmpty()) {
            allowedTools = toolDefinitions.stream().map(ToolDefinition::name).toList();
        } else {
            allowedTools = List.copyOf(allowedTools);
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 兼容构造：只提供工具名。
     * <p>
     * 这种请求无法把工具的参数结构告诉模型，模型只能靠猜参数名。新代码应当使用
     * 携带 {@link ToolDefinition} 的构造，让适配器拿到完整 schema。
     */
    public ModelRequest(List<ModelMessage> messages, ModelOptions options,
                        List<String> allowedTools, Map<String, Object> metadata) {
        this(messages, options, allowedTools, List.of(), metadata);
    }

    /** 是否携带了可用的工具定义（含输入 schema）。 */
    public boolean hasToolDefinitions() {
        return !toolDefinitions.isEmpty();
    }

    /** 按名字查找工具定义。 */
    public java.util.Optional<ToolDefinition> toolDefinition(String name) {
        return toolDefinitions.stream()
                .filter(definition -> definition.name().equals(name))
                .findFirst();
    }
}
