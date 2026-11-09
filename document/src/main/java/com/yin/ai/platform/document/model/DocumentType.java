package com.yin.ai.platform.document.model;

/**
 * 平台统一的文档类型枚举 — 与具体 Parser 解耦。
 */
public enum DocumentType {
    MARKDOWN,
    HTML,
    PDF,
    DOCX,
    XLSX,
    PPTX,
    TXT,
    CSV,
    JSON,
    XML,
    IMAGE,
    EMAIL,
    ZIP,
    WEB_PAGE,
    UNKNOWN;
}
