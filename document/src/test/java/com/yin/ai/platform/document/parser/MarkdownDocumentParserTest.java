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

@DisplayName("MarkdownDocumentParser")
class MarkdownDocumentParserTest {

    private MarkdownDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarkdownDocumentParser();
    }

    @Test
    @DisplayName("支持 MARKDOWN 类型")
    void shouldSupportMarkdown() {
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).build();
        assertThat(parser.supports(DocumentInput.builder().build(), info)).isTrue();
    }

    @Test
    @DisplayName("不支持 HTML 类型")
    void shouldNotSupportHtml() {
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML).build();
        assertThat(parser.supports(DocumentInput.builder().build(), info)).isFalse();
    }

    @Test
    @DisplayName("解析基本 Markdown 内容")
    void shouldParseBasicMarkdown() throws DocumentParseException {
        String md = "# Hello World\n\nThis is content.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN)
                .mimeType("text/markdown")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getTitle()).isEqualTo("Hello World");
        assertThat(result.getContent()).contains("This is content.");
        assertThat(result.getDocumentType()).isEqualTo(DocumentType.MARKDOWN);
        assertThat(result.getParserName()).isEqualTo("MarkdownDocumentParser");
        assertThat(result.getMetadata()).containsEntry("fileName", "test.md");
        assertThat(result.getMetadata()).containsEntry("fileSize", (long) md.length());
    }

    @Test
    @DisplayName("提取 YAML Frontmatter 中的 title")
    void shouldExtractTitleFromFrontmatter() throws DocumentParseException {
        String md = "---\ntitle: 测试文档\nauthor: yin\n---\n\n# Content\n\nBody text.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("doc.md")
                .size(md.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN)
                .mimeType("text/markdown")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getTitle()).isEqualTo("测试文档");
        assertThat(result.getAuthor()).isEqualTo("yin");
        assertThat(result.getContent()).doesNotContain("title: 测试文档");
        assertThat(result.getContent()).contains("# Content");
        assertThat(result.getMetadata()).containsEntry("fileName", "doc.md");
    }

    @Test
    @DisplayName("空 Markdown 文件正确处理")
    void shouldHandleEmptyMarkdown() throws DocumentParseException {
        DocumentInput input = DocumentInput.builder()
                .rawBytes("".getBytes(StandardCharsets.UTF_8))
                .fileName("empty.md")
                .size(0)
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN)
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getDocumentId()).isNotNull();
    }

    @Test
    @DisplayName("提取 Frontmatter 中自定义元数据字段")
    void shouldExtractCustomMetadataFromFrontmatter() throws DocumentParseException {
        String md = "---\ntitle: AI报告\ndate: 2025-06-01\n"
                + "tags: ai, ml, nlp\nstatus: draft\nviews: 1024\n"
                + "published: true\n---\n\n# Content\n\n正文内容。";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("report.md")
                .size(md.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN)
                .mimeType("text/markdown")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getTitle()).isEqualTo("AI报告");
        assertThat(result.getCreateTime()).isEqualTo("2025-06-01");
        assertThat(result.getMetadata()).containsEntry("tags", "ai, ml, nlp");
        assertThat(result.getMetadata()).containsEntry("status", "draft");
        assertThat(result.getMetadata()).containsEntry("views", 1024);
        assertThat(result.getMetadata()).containsEntry("published", Boolean.TRUE);
        assertThat(result.getMetadata()).containsEntry("fileName", "report.md");
    }

    @Test
    @DisplayName("文档内容长度正确记录")
    void shouldRecordContentLength() throws DocumentParseException {
        String md = "# Title\n\nContent line.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN)
                .mimeType("text/markdown")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getContentLength()).isGreaterThan(0);
    }
}
