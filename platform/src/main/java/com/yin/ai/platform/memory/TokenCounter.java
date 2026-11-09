package com.yin.ai.platform.memory;

/**
 * Token 估算器 — 基于字符数的简单估算。
 * <p>
 * 不支持精确 tokenizer（避免引入 heavyweight 依赖），
 * 使用经验比例 ~2.5 字符/Token（英语 ~4, 中文 ~1.5, 混合 ~2.5）。
 */
public class TokenCounter {

    /** 每个 Token 的平均字符数（混合中英文经验值） */
    private static final double CHARS_PER_TOKEN = 2.5;

    private TokenCounter() {}

    /**
     * 估算文本的 Token 数。
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 估算多条消息的总 Token 数。
     */
    public static int estimateTotal(java.util.List<?> messages) {
        return messages.stream()
                .mapToInt(msg -> {
                    if (msg instanceof SessionMemoryManager.ChatMessage m) {
                        return estimate(m.content());
                    }
                    return 0;
                })
                .sum();
    }
}
