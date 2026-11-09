package com.yin.ai.platform.document.model.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 列表节点 — 对应 Markdown {@code - / * / 1.} 列表。
 */
public final class ListNode extends DocumentNode {

    private boolean ordered;
    private List<ListItemNode> items;

    public ListNode() {
        super(NodeType.LIST);
        this.items = new ArrayList<>();
    }

    public boolean isOrdered() {
        return ordered;
    }

    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
    }

    public List<ListItemNode> getItems() {
        return items;
    }

    public void setItems(List<ListItemNode> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    @Override
    public String text() {
        return items.stream().map(ListItemNode::text).collect(Collectors.joining("\n"));
    }
}
