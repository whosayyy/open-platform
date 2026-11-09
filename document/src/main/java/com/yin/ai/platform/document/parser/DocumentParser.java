package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;

import java.util.Set;

/**
 * DocumentParser SPI — 文档解析器。
 * <p>
 * 每个实现负责解析一种或多种 {@link DocumentType}。Parser 之间互不依赖。
 * 新增 Parser 只需实现此接口并注册为 Spring Bean。
 * <p>
 * V2.0: 新增 {@link #parseToAST(DocumentInput, DocumentTypeInfo)} 方法，
 * 产出结构化 AST 而非纯文本。V1.0 的 {@link #parse(DocumentInput, DocumentTypeInfo)} 保留兼容。
 */
public interface DocumentParser {

    default String name() {
        return getClass().getSimpleName();
    }

    default int priority() {
        return 0;
    }

    default String version() {
        return "1.0.0";
    }

    boolean supports(DocumentInput input, DocumentTypeInfo typeInfo);

    Set<DocumentType> supportedTypes();

    /**
     * V1.0 兼容方法 — 产出基于 String 的 ParsedDocument。
     * <p>
     * V2.0 实现类应当同时实现 {@link #parseToAST(DocumentInput, DocumentTypeInfo)}，
     * 此方法可委托 AST 的 text() 结果构建 ParsedDocument。
     */
    ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException;

    /**
     * V2.0: 解析为结构化 DocumentAST。
     * <p>
     * 默认实现委托 V1.0 的 {@link #parse} 并包装为简单 AST。
     * 支持 AST 的 Parser 应覆盖此方法。
     */
    default DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        ParsedDocument doc = parse(input, typeInfo);
        return wrapAsAST(doc);
    }

    /**
     * 将 V1.0 ParsedDocument 包装为简单 AST（单 Paragraph 节点）。
     */
    private static DocumentAST wrapAsAST(ParsedDocument doc) {
        RootNode root = new RootNode();
        root.setTitle(doc.getTitle());
        root.setAuthor(doc.getAuthor());
        root.setStartOffset(0);
        root.setEndOffset(doc.getContentLength());

        if (doc.getContent() != null && !doc.getContent().isEmpty()) {
            ParagraphNode para = new ParagraphNode();
            para.setText(doc.getContent());
            para.setStartOffset(0);
            para.setEndOffset(doc.getContentLength());
            root.addChild(para);
        }

        DocumentAST ast = DocumentAST.of(root);
        ast.setDocumentId(doc.getDocumentId());
        return ast;
    }
}
