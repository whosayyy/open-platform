package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;
import com.yin.ai.platform.function.Function;
import com.yin.ai.platform.function.FunctionInput;
import com.yin.ai.platform.function.FunctionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-release compatibility bridge from the legacy Function API to core Tool. */
@SuppressWarnings("deprecation")
public final class LegacyFunctionToolAdapter implements Tool {

    private final Function delegate;
    private final ToolDefinition definition;

    public LegacyFunctionToolAdapter(Function delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.delegate = delegate;
        var legacy = delegate.getDefinition();
        this.definition = new ToolDefinition(legacy.getName(), legacy.getDescription(), legacy.getVersion(),
                inputSchema(legacy.getParameters()), legacy.getTags(),
                ToolDefinition.Risk.valueOf(legacy.getRisk().name()));
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        ExecutionContext context = input.executionContext();
        FunctionInput legacyInput = FunctionInput.builder()
                .parameters(input.parameters())
                .requestId(input.requestId())
                .traceId(context != null ? context.traceId() : null)
                .caller(context != null ? context.caller() : null)
                .tenantId(context != null ? context.tenantId() : null)
                .userId(context != null ? context.userId() : null)
                .roles(stringSet(context, "roles"))
                .permissions(stringSet(context, "permissions"))
                .build();
        FunctionResult result = delegate.execute(legacyInput);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("durationMs", result.getDurationMs());
        metadata.put("idempotentHit", result.isIdempotentHit());
        return new ToolResult(result.isSuccess(), result.getData(), result.getErrorCode(),
                result.getErrorMessage(), metadata);
    }

    private Map<String, Object> inputSchema(List<com.yin.ai.platform.function.FunctionDefinition.ParamDef> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        java.util.ArrayList<String> required = new java.util.ArrayList<>();
        if (parameters != null) {
            for (var parameter : parameters) {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", parameter.getType().name().toLowerCase());
                if (parameter.getDescription() != null) property.put("description", parameter.getDescription());
                if (parameter.getDefaultValue() != null) property.put("default", parameter.getDefaultValue());
                properties.put(parameter.getName(), property);
                if (parameter.isRequired()) required.add(parameter.getName());
            }
        }
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(required));
    }

    private Set<String> stringSet(ExecutionContext context, String key) {
        if (context == null) return Set.of();
        Object raw = context.attributes().get(key);
        if (raw instanceof Iterable<?> values) {
            java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
            values.forEach(value -> result.add(String.valueOf(value)));
            return Set.copyOf(result);
        }
        return Set.of();
    }
}
