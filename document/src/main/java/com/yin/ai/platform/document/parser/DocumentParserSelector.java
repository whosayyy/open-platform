package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentTypeInfo;

import java.util.Optional;

/**
 * DocumentParserSelector SPI — 根据文档特征自动选择最佳 Parser。
 */
public interface DocumentParserSelector {

    default String name() {
        return getClass().getSimpleName();
    }

    Optional<DocumentParser> select(DocumentInput input, DocumentTypeInfo typeInfo);
}
