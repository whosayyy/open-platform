package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TxtDocumentParser")
class TxtDocumentParserTest {

    private TxtDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new TxtDocumentParser();
    }

    @Test
    @DisplayName("支持 TXT 和 UNKNOWN 类型")
    void shouldSupportTxtAndUnknown() {
        DocumentTypeInfo txtInfo = DocumentTypeInfo.builder().documentType(DocumentType.TXT).build();
        DocumentTypeInfo unknownInfo = DocumentTypeInfo.builder().documentType(DocumentType.UNKNOWN).build();
        DocumentTypeInfo htmlInfo = DocumentTypeInfo.builder().documentType(DocumentType.HTML).build();

        assertThat(parser.supports(DocumentInput.builder().build(), txtInfo)).isTrue();
        assertThat(parser.supports(DocumentInput.builder().build(), unknownInfo)).isTrue();
        assertThat(parser.supports(DocumentInput.builder().build(), htmlInfo)).isFalse();
    }

    @Test
    @DisplayName("解析 UTF-8 文本")
    void shouldParseUtf8Text() throws DocumentParseException {
        String text = "纯文本内容\n第二行";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(text.getBytes(StandardCharsets.UTF_8))
                .fileName("notes.txt")
                .size(text.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.TXT)
                .mimeType("text/plain")
                .build();

        ParsedDocument result = parser.parse(input, info);

        // 解析器契约：Markdown 标题（文件名去后缀）+ 正文
        assertThat(result.getContent()).isEqualTo("# notes\n\n纯文本内容\n第二行");
        assertThat(result.getTitle()).isEqualTo("notes");
        assertThat(result.getParserName()).isEqualTo("TxtDocumentParser");
        assertThat(result.getDocumentType()).isEqualTo(DocumentType.TXT);
        assertThat(result.getMetadata()).containsEntry("fileName", "notes.txt");
        assertThat(result.getMetadata()).containsEntry("encoding", "UTF-8");
    }

    @Test
    @DisplayName("空文件正确处理")
    void shouldHandleEmptyFile() throws DocumentParseException {
        DocumentInput input = DocumentInput.builder()
                .rawBytes("".getBytes(StandardCharsets.UTF_8))
                .fileName("empty.txt")
                .size(0)
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.TXT).build();

        ParsedDocument result = parser.parse(input, info);

        // 空正文也保留 Markdown 标题头，正文为空
        assertThat(result.getContent()).isEqualTo("# empty\n\n");
        assertThat(result.getDocumentId()).isNotNull();
    }

    @Test
    @DisplayName("兜底解析器优先级最低")
    void shouldHaveLowestPriority() {
        assertThat(parser.priority()).isEqualTo(10);
    }
}
