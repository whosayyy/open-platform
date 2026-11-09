package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ParagraphChunkSplitter")
class ParagraphChunkSplitterTest {

    private ParagraphChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new ParagraphChunkSplitter(500, 50);
    }

    @Test
    @DisplayName("按双换行切分段落")
    void shouldSplitByDoubleNewline() {
        String content = "段落一\n\n段落二\n\n段落三";
        ParsedDocument doc = ParsedDocument.builder().content(content).build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).getContent()).isEqualTo("段落一");
        assertThat(segments.get(1).getContent()).isEqualTo("段落二");
        assertThat(segments.get(2).getContent()).isEqualTo("段落三");
    }

    @Test
    @DisplayName("单段落返回一个 Segment")
    void shouldReturnSingleSegmentForOneParagraph() {
        String content = "这是唯一的一个段落";
        ParsedDocument doc = ParsedDocument.builder().content(content).build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).getSegmentType()).isEqualTo(DocumentSegment.SegmentType.PARAGRAPH);
    }

    @Test
    @DisplayName("空内容返回空列表")
    void shouldReturnEmptyForEmptyContent() {
        ParsedDocument doc = ParsedDocument.builder().content("").build();
        assertThat(splitter.split(doc)).isEmpty();
    }

    @Test
    @DisplayName("大段落按 maxChunkSize 切分并保持 overlap")
    void shouldSplitOversizedParagraph() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("The quick brown fox jumps over the lazy dog. ");
        }
        String content = sb.toString();
        ParsedDocument doc = ParsedDocument.builder().content(content).build();

        ParagraphChunkSplitter splitSmall = new ParagraphChunkSplitter(100, 20);
        List<DocumentSegment> segments = splitSmall.split(doc);

        assertThat(segments).hasSizeGreaterThan(1);
        for (DocumentSegment seg : segments) {
            assertThat(seg.getContent().length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("supports 检查内容非空")
    void shouldSupportNonEmptyContent() {
        assertThat(splitter.supports(ParsedDocument.builder().content("text").build())).isTrue();
        assertThat(splitter.supports(ParsedDocument.builder().content("").build())).isFalse();
        assertThat(splitter.supports(ParsedDocument.builder().content(null).build())).isFalse();
    }
}
