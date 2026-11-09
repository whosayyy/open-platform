package com.yin.ai.platform.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 响应 JSON 解析器。
 * <p>
 * 从 AiResponseParser 迁移，负责将 LLM 文本响应解析为结构化 Java 对象。
 */
@Slf4j
public class ResponseParser {

    private final ObjectMapper objectMapper;

    public ResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 LLM 响应文本中提取 JSON 并反序列化。
     */
    public <T> T parse(String responseText, Class<T> targetClass) {
        if (responseText == null || responseText.isBlank()) {
            throw new RuntimeException("Empty response text");
        }

        String json = extractJson(responseText);
        try {
            return objectMapper.readValue(json, targetClass);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM response as {}: {}", targetClass.getSimpleName(), json, e);
            throw new RuntimeException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 子串。
     * 处理 LLM 输出被 Markdown 代码块包裹的情况。
     */
    private String extractJson(String text) {
        String trimmed = text.trim();

        // 处理 ```json ... ``` 包裹
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n");
            int end = trimmed.lastIndexOf("```");
            if (start >= 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }

        // 处理直接的 JSON
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }
}
