package com.yin.ai.platform.document.config;

import lombok.Data;

/**
 * Document Runtime 全局配置 — 绑定 {@code ai.document.*}。
 */
@Data
public class DocumentProperties {

    private boolean enabled = true;
    private int maxFileSizeBytes = 50 * 1024 * 1024;
    // P3-2 修复：2000 → 1200（BGE-small-zh 最大 512 token ≈ 1280 字符，2000 会截断 embedding）
    private int defaultChunkSize = 1200;
    private int defaultChunkOverlap = 150;
    private String defaultEncoding = "UTF-8";
}
