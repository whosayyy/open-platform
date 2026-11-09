package com.yin.ai.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 响应解析工具类 — 通用 JSON 清洗与解析逻辑。
 */
@Slf4j
public final class ResponseParseUtils {

    private static final int DEFAULT_MAX_CHARS = 120;

    private ResponseParseUtils() {}

    public static String cleanMarkdown(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("```json\\s*", "")
                            .replaceAll("```\\s*", "")
                            .trim();
        return extractJsonObject(cleaned);
    }

    /**
     * 从可能包含对话前缀/后缀的文本中提取 JSON 对象。
     * 例如 "好的，这是结果：{\"summary\":\"...\"}" 会被截取为 "{\"summary\":\"...\"}"。
     */
    public static String extractJsonObject(String text) {
        if (text == null || text.isEmpty()) return text;
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1).trim();
        }
        return text;
    }

    public static <T> T parseJson(String rawResponse, Class<T> clazz, ObjectMapper objectMapper) {
        String cleaned = cleanMarkdown(rawResponse);
        try {
            return objectMapper.readValue(cleaned, clazz);
        } catch (Exception e) {
            log.error("JSON 解析失败: {}", cleaned, e);
            throw new RuntimeException("AI 响应 JSON 解析失败", e);
        }
    }

    public static String fallbackSummary(String content) {
        return fallbackSummary(content, DEFAULT_MAX_CHARS);
    }

    public static String fallbackSummary(String content, int maxChars) {
        if (content == null || content.isEmpty()) return "";
        String plainText = content.replaceAll("<[^>]+>", "")
                                  .replaceAll("\\s+", " ")
                                  .trim();
        return plainText.length() > maxChars
                ? plainText.substring(0, maxChars) + "..."
                : plainText;
    }
}
