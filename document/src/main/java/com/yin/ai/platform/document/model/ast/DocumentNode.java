package com.yin.ai.platform.document.model.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Document AST 节点抽象基类 — 所有 AST 节点的共同父类。
 * <p>
 * 使用 {@code sealed} 限制子类型，确保类型安全。
 * Sibling 关系由 {@link DocumentAST} 在操作树时同步维护。
 */
public abstract sealed class DocumentNode
        permits RootNode, SectionNode, ParagraphNode, TableNode, TableRowNode, TableCellNode,
                ImageNode, CodeBlockNode, ListNode, ListItemNode, QuoteNode, FormulaNode, PageNode, EntityNode {

    private final String id;
    private final NodeType nodeType;
    private DocumentNode parent;
    private final List<DocumentNode> children;
    /**
     * 节点在树中的 sibling 顺序，如 3 (第3个child)。
     */
    private DocumentNode previousSibling;
    /**
     * 节点的下一个兄弟节点。
     */
    private DocumentNode nextSibling;
    private int depth;
    private long startOffset;
    private long endOffset;
    private Integer page;
    private Map<String, Object> metadata;

    protected DocumentNode(NodeType nodeType) {
        this.id = UUID.randomUUID().toString();
        this.nodeType = nodeType;
        this.children = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public String getId() {
        return id;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public DocumentNode getParent() {
        return parent;
    }

    void setParent(DocumentNode parent) {
        this.parent = parent;
    }

    public List<DocumentNode> getChildren() {
        return children;
    }

    /**
     * 添加子节点 — Parser/Pipeline 通过此方法构建 AST 树。
     */
    public void addChild(DocumentNode child) {
        children.add(child);
        child.setParent(this);
    }

    public DocumentNode getPreviousSibling() {
        return previousSibling;
    }

    void setPreviousSibling(DocumentNode previousSibling) {
        this.previousSibling = previousSibling;
    }

    public DocumentNode getNextSibling() {
        return nextSibling;
    }

    void setNextSibling(DocumentNode nextSibling) {
        this.nextSibling = nextSibling;
    }

    public int getDepth() {
        return depth;
    }

    void setDepth(int depth) {
        this.depth = depth;
    }

    public long getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(long startOffset) {
        this.startOffset = startOffset;
    }

    public long getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(long endOffset) {
        this.endOffset = endOffset;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    /**
     * 节点在树中的路径索引，如 "/0/3/1" (root → 第3个child → 第1个child)。
     */
    public String path() {
        if (parent == null) {
            return "/" + id;
        }
        return parent.path() + "/" + id;
    }

    /**
     * 查找最近的 SectionNode 祖先。
     */
    public SectionNode findAncestorSection() {
        DocumentNode current = parent;
        while (current != null) {
            if (current instanceof SectionNode section) {
                return section;
            }
            current = current.getParent();
        }
        return null;
    }

    /** 收集此节点子树的纯文本内容（用于 RAG embedding）。 */
    public abstract String text();

    @Override
    public String toString() {
        return nodeType + "{" + "id='" + id + '\'' + ", depth=" + depth + '}';
    }
}
