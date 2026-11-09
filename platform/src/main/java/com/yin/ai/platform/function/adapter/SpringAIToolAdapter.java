package com.yin.ai.platform.function.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.function.Function;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionInput;
import com.yin.ai.platform.function.FunctionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI 协议适配器 — 将 Function Framework 的 Function 包装为 Spring AI 的工具回调。
 * <p>
 * 使用方式：{@code SpringAIToolAdapter.toToolCallback(myFunction)}
 * 返回的 ToolCallback 可直接传入 ChatClient.prompt().tools(callbacks)。
 */
@Slf4j
public final class SpringAIToolAdapter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private SpringAIToolAdapter() {
    }

    /**
     * 将 Function 转换为 Spring AI ToolCallback。
     */
    public static ToolCallback toToolCallback(Function fn) {
        return new FunctionToolCallback(fn);
    }

    private static String buildInputSchemaJson(FunctionDefinition def) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        if (def.getParameters() != null) {
            for (FunctionDefinition.ParamDef param : def.getParameters()) {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", param.getType().name().toLowerCase());
                prop.put("description", param.getDescription());
                if (param.getDefaultValue() != null) {
                    prop.put("default", param.getDefaultValue());
                }
                properties.put(param.getName(), prop);
            }
        }
        params.put("properties", properties);

        if (def.getParameters() != null) {
            var required = def.getParameters().stream()
                    .filter(FunctionDefinition.ParamDef::isRequired)
                    .map(FunctionDefinition.ParamDef::getName)
                    .toList();
            if (!required.isEmpty()) {
                params.put("required", required);
            }
        }

        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize input schema for function: {}", def.getName(), e);
            return "{}";
        }
    }

    private record FunctionToolCallback(Function fn) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            FunctionDefinition def = fn.getDefinition();
            return DefaultToolDefinition.builder()
                    .name(def.getName())
                    .description(def.getDescription())
                    .inputSchema(buildInputSchemaJson(def))
                    .build();
        }

        @Override
        @SuppressWarnings("unchecked")
        public String call(String toolInput) {
            try {
                Map<String, Object> params;
                if (toolInput == null || toolInput.isBlank()) {
                    params = Map.of();
                } else {
                    params = objectMapper.readValue(toolInput, Map.class);
                }

                FunctionResult result = fn.execute(FunctionInput.builder()
                        .parameters(params)
                        .caller(fn.getDefinition().getName())
                        // [全链路追踪] LLM 工具回调透传业务 traceId（引擎线程 MDC）
                        .traceId(org.slf4j.MDC.get("traceId"))
                        .build());

                if (result.isSuccess()) {
                    Object data = result.getData();
                    if (data instanceof String s) {
                        return s;
                    }
                    return objectMapper.writeValueAsString(data);
                }
                return "{\"error\": \"" + escapeJson(result.getErrorMessage()) + "\"}";
            } catch (Exception e) {
                log.error("Function callback failed: {}", fn.getDefinition().getName(), e);
                return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
            }
        }

        private static String escapeJson(String s) {
            if (s == null) return "null";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
