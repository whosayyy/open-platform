package com.yin.ai.platform.document.model.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 段落节点 — 对应文本中的自然段落。
 */
public final class ParagraphNode extends DocumentNode {

    private List<Span> sentences;

    public ParagraphNode() {
        super(NodeType.PARAGRAPH);
        this.sentences = new ArrayList<>();
    }

    public List<Span> getSentences() {
        return sentences;
    }

    public void setSentences(List<Span> sentences) {
        this.sentences = sentences != null ? sentences : new ArrayList<>();
    }

    public void setText(String text) {
        this.sentences = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            this.sentences.add(Span.of(text, 0));
        }
    }

    @Override
    public String text() {
        return sentences.stream().map(Span::text).collect(Collectors.joining());
    }
}
