package com.yin.ai.platform.document.model.ast;

/**
 * 文本片段 — 字符级精确定位，用于高亮和溯源。
 */
public record Span(
        String text,
        int startOffset,
        int endOffset) {

    public int length() {
        return endOffset - startOffset;
    }

    public static Span of(String text, int start) {
        return new Span(text, start, start + text.length());
    }
}
