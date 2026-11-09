package com.yin.ai.platform.document.model.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表格节点 — 包含表头和行数据。
 */
public final class TableNode extends DocumentNode {

    private List<String> headers;
    private List<TableRowNode> rows;
    private List<String> columnAlignments;

    public TableNode() {
        super(NodeType.TABLE);
        this.headers = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.columnAlignments = new ArrayList<>();
    }

    public List<String> getHeaders() {
        return headers;
    }

    public void setHeaders(List<String> headers) {
        this.headers = headers != null ? headers : new ArrayList<>();
    }

    public List<TableRowNode> getRows() {
        return rows;
    }

    public void setRows(List<TableRowNode> rows) {
        this.rows = rows != null ? rows : new ArrayList<>();
    }

    public List<String> getColumnAlignments() {
        return columnAlignments;
    }

    public void setColumnAlignments(List<String> columnAlignments) {
        this.columnAlignments = columnAlignments != null ? columnAlignments : new ArrayList<>();
    }

    @Override
    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("| ").append(
                columnAlignments.stream().map(a -> switch (a) {
                    case "LEFT" -> ":---";
                    case "RIGHT" -> "---:";
                    case "CENTER" -> ":---:";
                    default -> "---";
                }).collect(Collectors.joining(" | "))
        ).append(" |\n");
        for (TableRowNode row : rows) {
            sb.append(row.text()).append('\n');
        }
        return sb.toString();
    }
}
