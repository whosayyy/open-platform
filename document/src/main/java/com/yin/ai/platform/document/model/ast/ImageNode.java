package com.yin.ai.platform.document.model.ast;

/**
 * 图片节点 — 包含位置、描述和 OCR 文本。
 */
public final class ImageNode extends DocumentNode {

    private String alt;
    private Integer width;
    private Integer height;
    private String caption;
    private String ocrText;
    private String url;

    public ImageNode() {
        super(NodeType.IMAGE);
    }

    public String getAlt() {
        return alt;
    }

    public void setAlt(String alt) {
        this.alt = alt;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public String getOcrText() {
        return ocrText;
    }

    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String text() {
        if (ocrText != null && !ocrText.isEmpty()) {
            return ocrText;
        }
        return alt != null ? alt : "";
    }
}
