package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.chunk.DocumentChunk;

import java.util.List;

/**
 * ChunkSplitter SPI — 文档切分器。
 * <p>
 * V1.0: {@link #split(ParsedDocument)} 基于 String 切分为 {@link DocumentSegment}。
 * V2.0: {@link #splitAST(DocumentAST)} 基于 AST 切分为 {@link DocumentChunk}。
 * 实现类可以同时支持两个版本用于渐进迁移。
 */
public interface ChunkSplitter {

    default String name() {
        return getClass().getSimpleName();
    }

    default int priority() {
        return 0;
    }

    // —— V1.0 ——

    boolean supports(ParsedDocument document);

    List<DocumentSegment> split(ParsedDocument document);

    // —— V2.0 ——

    /**
     * V2.0: 基于 AST 的切分，返回引用 AST Node 的 DocumentChunk。
     * 默认返回空列表，支持 AST 的 Splitter 应覆盖此方法。
     */
    default boolean supportsAST(DocumentAST ast) {
        return false;
    }

    default List<DocumentChunk> splitAST(DocumentAST ast) {
        return List.of();
    }
}
