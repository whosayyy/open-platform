package com.yin.ai.platform.document.detector;

import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentTypeInfo;

/**
 * DocumentDetector SPI — 分析 {@link DocumentInput} 确定文档类型。
 */
public interface DocumentDetector {

    default String name() {
        return getClass().getSimpleName();
    }

    DocumentTypeInfo detect(DocumentInput input);
}
