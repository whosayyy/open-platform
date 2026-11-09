package com.yin.ai.platform.document.model.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表格行节点。
 */
public final class TableRowNode extends DocumentNode {

    private List<TableCellNode> cells;

    public TableRowNode() {
        super(NodeType.TABLE_ROW);
        this.cells = new ArrayList<>();
    }

    public List<TableCellNode> getCells() {
        return cells;
    }

    public void setCells(List<TableCellNode> cells) {
        this.cells = cells != null ? cells : new ArrayList<>();
    }

    @Override
    public String text() {
        return "| " + cells.stream().map(TableCellNode::getText).collect(Collectors.joining(" | ")) + " |";
    }
}
