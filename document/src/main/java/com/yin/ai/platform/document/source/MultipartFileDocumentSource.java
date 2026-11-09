package com.yin.ai.platform.document.source;

import com.yin.ai.platform.document.model.DocumentInput;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 从 Spring {@link MultipartFile} 构建 {@link DocumentInput}。
 */
public class MultipartFileDocumentSource implements DocumentSource {

    private final MultipartFile file;
    private final Map<String, Object> metadata;

    public MultipartFileDocumentSource(MultipartFile file) {
        this(file, Map.of());
    }

    public MultipartFileDocumentSource(MultipartFile file, Map<String, Object> metadata) {
        this.file = file;
        this.metadata = metadata;
    }

    @Override
    public DocumentInput resolve() throws IOException {
        return DocumentInput.builder()
                .rawBytes(file.getBytes())
                .fileName(file.getOriginalFilename())
                .sourceType("MULTIPART_FILE")
                .size(file.getSize())
                .contentTypeHint(file.getContentType())
                .sourceMetadata(metadata)
                .build();
    }
}
