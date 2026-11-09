package com.yin.ai.platform.document.model.chunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V2.0 Document Chunk — AST 的 Projection（投影）。
 * <p>
 * Chunk 不持有权威文本数据，而是通过 {@code nodeRefs} 引用 AST 中的 Node。
 * 重新 Chunk 只需修改引用关系，无需重新 Parse。
 */
public class DocumentChunk {

    private String chunkId;
    private String documentId;

    // AST 引用
    private List<String> nodeRefs;
    private String headingPath;
    private List<String> headingNodeIds;

    // 位置
    private int chunkIndex;
    private long startOffset;
    private long endOffset;
    private Integer page;

    // 内容缓存（从 AST 计算，非权威）
    private String content;

    // 元数据
    private Map<String, Object> chunkMetadata;

    public DocumentChunk() {
        this.chunkMetadata = new HashMap<>();
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public List<String> getNodeRefs() {
        return nodeRefs;
    }

    public void setNodeRefs(List<String> nodeRefs) {
        this.nodeRefs = nodeRefs;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String headingPath) {
        this.headingPath = headingPath;
    }

    public List<String> getHeadingNodeIds() {
        return headingNodeIds;
    }

    public void setHeadingNodeIds(List<String> headingNodeIds) {
        this.headingNodeIds = headingNodeIds;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getChunkMetadata() {
        return chunkMetadata;
    }

    public void setChunkMetadata(Map<String, Object> chunkMetadata) {
        this.chunkMetadata = chunkMetadata != null ? chunkMetadata : new HashMap<>();
    }

    @Override
    public String toString() {
        return "DocumentChunk{" + "id='" + chunkId + '\'' + ", index=" + chunkIndex
                + ", nodes=" + (nodeRefs != null ? nodeRefs.size() : 0) + '}';
    }
}
