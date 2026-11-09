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

@DisplayName("HtmlDocumentParser")
class HtmlDocumentParserTest {

    private HtmlDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new HtmlDocumentParser();
    }

    @Test
    @DisplayName("支持 HTML 类型")
    void shouldSupportHtml() {
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML).build();
        assertThat(parser.supports(DocumentInput.builder().build(), info)).isTrue();
    }

    @Test
    @DisplayName("解析基本 HTML，提取标题和正文")
    void shouldParseBasicHtml() throws DocumentParseException {
        String html = "<html><head><title>测试页面</title></head><body><p>Hello World</p></body></html>";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(html.getBytes(StandardCharsets.UTF_8))
                .fileName("page.html")
                .size(html.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML)
                .mimeType("text/html")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getTitle()).isEqualTo("测试页面");
        assertThat(result.getContent()).contains("Hello World");
        assertThat(result.getDocumentType()).isEqualTo(DocumentType.HTML);
        assertThat(result.getParserName()).isEqualTo("HtmlDocumentParser");
        assertThat(result.getMetadata()).containsEntry("fileName", "page.html");
        assertThat(result.getMetadata()).containsEntry("fileSize", (long) html.length());
    }

    @Test
    @DisplayName("移除 script 和 style 标签内容")
    void shouldStripScriptAndStyle() throws DocumentParseException {
        String html = "<html><head><style>body{color:red}</style><script>alert(1)</script></head>"
                + "<body><p>Visible text</p></body></html>";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(html.getBytes(StandardCharsets.UTF_8))
                .fileName("page.html")
                .size(html.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML)
                .mimeType("text/html")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getContent()).doesNotContain("alert(1)");
        assertThat(result.getContent()).doesNotContain("color:red");
        assertThat(result.getContent()).contains("Visible text");
    }

    @Test
    @DisplayName("提取 HTML meta 标签中的元数据")
    void shouldExtractMetaTags() throws DocumentParseException {
        String html = "<html><head><title>文章标题</title>"
                + "<meta name=\"author\" content=\"张三\">"
                + "<meta name=\"description\" content=\"这是一篇关于AI的文章\">"
                + "<meta name=\"keywords\" content=\"AI, NLP, 机器学习\">"
                + "<meta name=\"date\" content=\"2025-06-01\">"
                + "<meta property=\"og:description\" content=\"OG描述\">"
                + "<meta property=\"og:image\" content=\"https://example.com/img.jpg\">"
                + "</head><body><p>正文内容</p></body></html>";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(html.getBytes(StandardCharsets.UTF_8))
                .fileName("article.html")
                .size(html.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML)
                .mimeType("text/html")
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getTitle()).isEqualTo("文章标题");
        assertThat(result.getAuthor()).isEqualTo("张三");
        assertThat(result.getCreateTime()).isEqualTo("2025-06-01");
        assertThat(result.getMetadata()).containsEntry("description", "这是一篇关于AI的文章");
        assertThat(result.getMetadata()).containsEntry("keywords", "AI, NLP, 机器学习");
        assertThat(result.getMetadata()).containsEntry("ogDescription", "OG描述");
        assertThat(result.getMetadata()).containsEntry("ogImage", "https://example.com/img.jpg");
        assertThat(result.getMetadata()).containsEntry("fileName", "article.html");
    }

    @Test
    @DisplayName("空 HTML 正确解析")
    void shouldHandleEmptyHtml() throws DocumentParseException {
        String html = "<html></html>";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(html.getBytes(StandardCharsets.UTF_8))
                .fileName("empty.html")
                .size(html.length())
                .build();

        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML)
                .build();

        ParsedDocument result = parser.parse(input, info);

        assertThat(result.getDocumentId()).isNotNull();
    }
}
