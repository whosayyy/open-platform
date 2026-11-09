package com.yin.ai.platform.document.processor;

import com.yin.ai.platform.document.model.ast.DocumentAST;

/**
 * DocumentProcessor SPI — AST 文档处理阶段。
 * <p>
 * 操作 {@link DocumentAST}（结构化 AST），区别于操作 {@code ParsedDocument}（纯文本）的
 * {@code DocumentCleaner}。多个 Processor 按 {@link #order()} 升序执行。
 */
public interface DocumentProcessor {

    default String name() {
        return getClass().getSimpleName();
    }

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 100;
    }

    void process(DocumentAST ast);
}
