package com.yin.ai.platform.document.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Pipeline 运行时覆盖选项 — 按调用场景动态调整行为。
 */
@Data
@Builder
public class DocumentRuntimeOptions {
    /** 使用 AST 模式（V2.0）：parseToAST() + DocumentProcessor 链 */
    private boolean astMode;
    private boolean skipCleaning;
    private boolean skipSplitting;
    private String forcedEncoding;
    private String preferredSplitter;
    private Map<String, Object> extras;
}
