package com.yin.ai.platform.memory.compression;

import com.yin.ai.platform.core.memory.MemoryType;

import java.util.HashMap;
import java.util.Map;

/**
 * 压缩上下文 — 携带压缩所需的参数和约束。
 *
 * @param maxTokens  Token 预算上限
 * @param memoryType 当前 Memory 类型
 * @param params     扩展参数（如 summarizationPrompt、targetLanguage 等）
 */
public record CompressionContext(int maxTokens, MemoryType memoryType, Map<String, Object> params) {

    public CompressionContext {
        params = params != null ? Map.copyOf(params) : Map.of();
    }

    public static CompressionContext of(int maxTokens, MemoryType memoryType) {
        return new CompressionContext(maxTokens, memoryType, Map.of());
    }

    public CompressionContext withParam(String key, Object value) {
        Map<String, Object> newParams = new HashMap<>(params);
        newParams.put(key, value);
        return new CompressionContext(maxTokens, memoryType, newParams);
    }
}
