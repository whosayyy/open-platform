package com.yin.ai.platform.document.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 统一文档输入 — 由 {@code DocumentSource.resolve()} 产出，供 Pipeline 下游统一消费。
 */
@Data
@Builder
public class DocumentInput {
    private byte[] rawBytes;
    private String fileName;
    private String sourceType;
    private long size;
    private String contentTypeHint;
    private Map<String, Object> sourceMetadata;
}
