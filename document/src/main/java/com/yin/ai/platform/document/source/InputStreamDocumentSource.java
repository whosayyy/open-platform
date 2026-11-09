package com.yin.ai.platform.document.source;

import com.yin.ai.platform.document.model.DocumentInput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 从 {@link InputStream} 构建 {@link DocumentInput}。
 */
public class InputStreamDocumentSource implements DocumentSource {

    private final InputStream inputStream;
    private final String fileName;
    private final Map<String, Object> metadata;

    public InputStreamDocumentSource(InputStream inputStream, String fileName) {
        this(inputStream, fileName, Map.of());
    }

    public InputStreamDocumentSource(InputStream inputStream, String fileName, Map<String, Object> metadata) {
        this.inputStream = inputStream;
        this.fileName = fileName;
        this.metadata = metadata;
    }

    @Override
    public DocumentInput resolve() throws IOException {
        //内存缓冲区
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        //缓冲区
        byte[] chunk = new byte[8192];
        int n;
        //从输入流中读取数据到缓冲区中
        while ((n = inputStream.read(chunk, 0, chunk.length)) != -1) {
            buffer.write(chunk, 0, n);
        }
        byte[] bytes = buffer.toByteArray();
        return DocumentInput.builder()
                .rawBytes(bytes)
                .fileName(fileName)
                .sourceType("INPUT_STREAM")
                .size(bytes.length)
                .sourceMetadata(metadata)
                .build();
    }
}
