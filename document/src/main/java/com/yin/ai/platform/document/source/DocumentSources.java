package com.yin.ai.platform.document.source;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

/**
 * DocumentSource 静态工厂 — 为常见输入类型提供便捷构造。
 */
public final class DocumentSources {

    private DocumentSources() {
    }

    public static DocumentSource fromBytes(byte[] bytes, String fileName) {
        return new ByteArrayDocumentSource(bytes, fileName);
    }

    public static DocumentSource fromBytes(byte[] bytes, String fileName, Map<String, Object> metadata) {
        return new ByteArrayDocumentSource(bytes, fileName, metadata);
    }

    public static DocumentSource fromInputStream(InputStream inputStream, String fileName) {
        return new InputStreamDocumentSource(inputStream, fileName);
    }

    public static DocumentSource fromMultipartFile(MultipartFile file) {
        return new MultipartFileDocumentSource(file);
    }
}
