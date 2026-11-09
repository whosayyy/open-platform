package com.yin.ai.platform.document.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 文档 Chunk — 由 {@code ChunkSplitter.split()} 产出，用于 RAG 索引。
 */
@Data
@Builder
public class DocumentSegment {
    private String segmentId;
    private String content;
    private SegmentType segmentType;
    private Integer startOffset;
    private Integer endOffset;
    private Integer headingLevel;
    private String parentHeading;
    private Map<String, Object> metadata;

    public enum SegmentType {
        PARAGRAPH,
        HEADING,
        TABLE,
        CODE_BLOCK,
        LIST_ITEM,
        BLOCKQUOTE,
        FORMULA;
    }
}
