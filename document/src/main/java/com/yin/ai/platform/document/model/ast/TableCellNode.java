package com.yin.ai.platform.document.model.ast;

/**
 * 表格单元格节点。
 */
public final class TableCellNode extends DocumentNode {

    private String text;
    private boolean isHeader;

    public TableCellNode() {
        super(NodeType.TABLE_CELL);
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isHeader() {
        return isHeader;
    }

    public void setHeader(boolean header) {
        isHeader = header;
    }

    @Override
    public String text() {
        return text != null ? text : "";
    }
}
