package com.yin.ai.platform.document.model.ast;

/**
 * 章节节点 — 对应 Markdown {@code # / ## / ###} 或 HTML {@code <h1>-<h6>}。
 */
public final class SectionNode extends DocumentNode {

    private String heading;
    private int level;
    private Integer number;

    public SectionNode() {
        super(NodeType.SECTION);
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    @Override
    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("#".repeat(Math.max(1, level))).append(' ').append(heading).append('\n');
        for (DocumentNode child : getChildren()) {
            sb.append(child.text());
        }
        return sb.toString();
    }
}
