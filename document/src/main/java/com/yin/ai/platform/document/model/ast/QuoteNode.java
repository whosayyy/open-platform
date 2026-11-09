package com.yin.ai.platform.document.model.ast;

/**
 * 引用节点 — 对应 Markdown {@code >} 块引用。
 */
public final class QuoteNode extends DocumentNode {

    private int level;
    private String attribution;

    public QuoteNode() {
        super(NodeType.QUOTE);
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getAttribution() {
        return attribution;
    }

    public void setAttribution(String attribution) {
        this.attribution = attribution;
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
