package com.yin.ai.platform.document.model.ast;

/**
 * 列表项节点。
 */
public final class ListItemNode extends DocumentNode {

    private int index;
    private String content;

    public ListItemNode() {
        super(NodeType.LIST_ITEM);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String text() {
        if (content != null) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (DocumentNode child : getChildren()) {
            sb.append(child.text());
        }
        return sb.toString();
    }
}
