package com.yin.ai.platform.document.model.ast;

/**
 * 文档根节点 — AST 树的根。
 */
public final class RootNode extends DocumentNode {

    private String title;
    private String language;
    private String author;
    private String sourceInfo;

    public RootNode() {
        super(NodeType.ROOT);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getSourceInfo() {
        return sourceInfo;
    }

    public void setSourceInfo(String sourceInfo) {
        this.sourceInfo = sourceInfo;
    }

    @Override
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (DocumentNode child : getChildren()) {
            sb.append(child.text());
        }
        return sb.toString();
    }
}
