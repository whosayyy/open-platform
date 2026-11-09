package com.yin.ai.platform.document.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 平台统一 ParsedDocument — Document Runtime 的最终产出。
 * <p>
 * 各 Pipeline 阶段逐步填充字段：
 * <ul>
 *   <li>{@code documentType / mimeType} — 由 Detector 设置</li>
 *   <li>{@code title / content} — 由 Parser 设置</li>
 *   <li>{@code parserName / parserVersion} — 由 Parser 设置</li>
 *   <li>{@code segments} — 由 ChunkSplitter 设置</li>
 * </ul>
 */
@Data
@Builder
public class ParsedDocument {
    private String documentId;
    private DocumentType documentType;
    private String mimeType;
    private String title;
    private String content;
    private long contentLength;
    private String parserName;
    private String parserVersion;
    private String language;
    private String author;
    private String createTime;
    private int pageCount;
    private Map<String, Object> metadata;
    @Builder.Default
    private List<String> warnings = List.of();
    @Builder.Default
    private List<DocumentSegment> segments = List.of();
}
