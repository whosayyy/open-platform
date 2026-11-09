package com.yin.ai.platform.document.cleaner;

import com.yin.ai.platform.document.model.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WhitespaceNormalizerDocumentCleaner")
class WhitespaceNormalizerDocumentCleanerTest {

    private WhitespaceNormalizerDocumentCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new WhitespaceNormalizerDocumentCleaner();
    }

    @Test
    @DisplayName("连续空行压缩为最多 2 行")
    void shouldCollapseMultipleBlankLines() {
        String content = "第一段\n\n\n\n\n第二段";
        ParsedDocument doc = ParsedDocument.builder().content(content).build();

        cleaner.clean(doc);

        assertThat(doc.getContent()).contains("第一段");
        assertThat(doc.getContent()).contains("第二段");
        assertThat(doc.getContent()).doesNotContain("\n\n\n\n");
    }

    @Test
    @DisplayName("去除行尾空白")
    void shouldStripTrailingWhitespace() {
        String content = "line1   \nline2\t\t\n";
        ParsedDocument doc = ParsedDocument.builder().content(content).build();

        cleaner.clean(doc);

        assertThat(doc.getContent()).doesNotContain("   \n");
        assertThat(doc.getContent()).doesNotContain("\t\t");
    }

    @Test
    @DisplayName("\\r\\n 统一转换为 \\n")
    void shouldNormalizeLineEndings() {
        String content = "line1\r\nline2\rline3\nline4";
        ParsedDocument doc = ParsedDocument.builder().content(content).build();

        cleaner.clean(doc);

        assertThat(doc.getContent()).doesNotContain("\r\n");
        assertThat(doc.getContent()).doesNotContain("\r");
    }

    @Test
    @DisplayName("空内容正确处理")
    void shouldHandleEmptyContent() {
        ParsedDocument doc = ParsedDocument.builder().content("").build();

        cleaner.clean(doc);

        assertThat(doc.getContent()).isEmpty();
    }

    @Test
    @DisplayName("null 内容正确处理")
    void shouldHandleNullContent() {
        ParsedDocument doc = ParsedDocument.builder().content(null).build();

        cleaner.clean(doc);

        assertThat(doc.getContent()).isNull();
    }

    @Test
    @DisplayName("contentLength 更新")
    void shouldUpdateContentLength() {
        String content = "line1\n\n\n\n\n\n\nline2";
        ParsedDocument doc = ParsedDocument.builder().content(content).contentLength(content.length()).build();

        cleaner.clean(doc);

        assertThat(doc.getContentLength()).isLessThan(content.length());
        assertThat(doc.getContentLength()).isEqualTo(doc.getContent().length());
    }
}
