package com.yin.ai.platform.document.source;

import com.yin.ai.platform.document.model.DocumentInput;

import java.io.IOException;
import java.util.Map;

/**
 * 从 byte[] 构建 {@link DocumentInput}。
 */
public class ByteArrayDocumentSource implements DocumentSource {

    private final byte[] bytes;
    private final String fileName;
    private final Map<String, Object> metadata;

    public ByteArrayDocumentSource(byte[] bytes, String fileName) {
        this(bytes, fileName, Map.of());
    }

    public ByteArrayDocumentSource(byte[] bytes, String fileName, Map<String, Object> metadata) {
        this.bytes = bytes;
        this.fileName = fileName;
        this.metadata = metadata;
    }

    @Override
    public DocumentInput resolve() throws IOException {
        return DocumentInput.builder()
                .rawBytes(bytes)
                .fileName(fileName)
                .sourceType("BYTE_ARRAY")
                .size(bytes.length)
                .sourceMetadata(metadata)
                .build();
    }
}
