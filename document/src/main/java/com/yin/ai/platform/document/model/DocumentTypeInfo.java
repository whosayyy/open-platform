package com.yin.ai.platform.document.model;

import lombok.Builder;
import lombok.Data;

/**
 * 文档类型检测结果 — 由 {@code DocumentDetector.detect()} 产出。
 */
@Data
@Builder
public class DocumentTypeInfo {
    // 文档类型
    private DocumentType documentType;
    // 文档类型对应的 MIME
    private String mimeType;
    // 文档类型对应的字符编码
    private String encoding;
    // 文档类型对应的文件扩展名
    private long fileSize;
    // 文档类型对应的文件扩展名
    private String extension;
    // 文档类型检测的置信度
    private double confidence;
    // 文档类型检测方法
    private String detectionMethod;
}
