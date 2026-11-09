package com.yin.ai.platform.document.source;

import com.yin.ai.platform.document.model.DocumentInput;

import java.io.IOException;

/**
 * DocumentSource SPI — 统一文档来源抽象。
 * <p>
 * 每种实现封装一种输入类型（byte[] / InputStream / MultipartFile / URL / OSS / S3），
 * 提供统一的 {@link DocumentInput} 给下游 Pipeline。
 */
public interface DocumentSource {

    default String name() {
        return getClass().getSimpleName();
    }

    DocumentInput resolve() throws IOException;
}
