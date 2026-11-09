package com.yin.ai.platform.document.model.ast;

/**
 * 代码块节点 — 对应 Markdown {@code ```} 围栏代码块。
 */
public final class CodeBlockNode extends DocumentNode {

    private String language;
    private String code;
    private int lineStart;
    private int lineEnd;

    public CodeBlockNode() {
        super(NodeType.CODE_BLOCK);
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getLineStart() {
        return lineStart;
    }

    public void setLineStart(int lineStart) {
        this.lineStart = lineStart;
    }

    public int getLineEnd() {
        return lineEnd;
    }

    public void setLineEnd(int lineEnd) {
        this.lineEnd = lineEnd;
    }

    @Override
    public String text() {
        return code;
    }
}
