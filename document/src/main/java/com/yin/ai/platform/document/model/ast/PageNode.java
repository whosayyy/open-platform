package com.yin.ai.platform.document.model.ast;

/**
 * 页面节点 — 用于 PDF/DOCX 等有明确分页的文档格式。
 */
public final class PageNode extends DocumentNode {

    private int pageNumber;
    private Double width;
    private Double height;

    public PageNode() {
        super(NodeType.PAGE);
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
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
