package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HeadingChunkSplitter 测试 — 生产主力拆分器（priority 100）。
 * 覆盖：标题栈追踪、代码块内 # 跳过、超长子切分（段落/句子 + overlap 衔接）、[来源:] 前缀注入。
 */
@DisplayName("HeadingChunkSplitter")
class HeadingChunkSplitterTest {

    private HeadingChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new HeadingChunkSplitter(500, 50);
    }

    @Test
    @DisplayName("按 Markdown 标题切分章节")
    void shouldSplitByHeadings() {
        String content = """
                # 第一章
                第一章正文内容。

                ## 第一节
                第一节正文内容。

                # 第二章
                第二章正文内容。
                """;
        ParsedDocument doc = ParsedDocument.builder()
                .title("测试文档")
                .content(content)
                .build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSize(3);
        // 每块 content 都包含 [来源:] 上下文前缀
        assertThat(segments.get(0).getContent()).startsWith("[来源: 测试文档 > 第一章]");
        assertThat(segments.get(1).getContent()).startsWith("[来源: 测试文档 > 第一章 > 第一节]");
        assertThat(segments.get(2).getContent()).startsWith("[来源: 测试文档 > 第二章]");
        // 父标题追踪正确
        assertThat(segments.get(1).getParentHeading()).isEqualTo("第一章");
        assertThat(segments.get(2).getParentHeading()).isNull();
    }

    @Test
    @DisplayName("代码块内的 # 不视为标题")
    void shouldSkipHashInsideCodeBlock() {
        String content = """
                # 章节
                ```java
                // # 这不是标题
                public class Test {
                    // ## 也不是标题
                }
                ```
                正文内容。
                """;
        ParsedDocument doc = ParsedDocument.builder()
                .title("测试文档")
                .content(content)
                .build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).getContent()).contains("public class Test");
        assertThat(segments.get(0).getContent()).contains("正文内容");
    }

    @Test
    @DisplayName("标题层级栈：子标题正确归属父标题")
    void shouldTrackHeadingHierarchy() {
        String content = """
                # 一级
                一级内容。

                ## 二级
                二级内容。

                ### 三级
                三级内容。

                ## 二级之二
                内容。
                """;
        ParsedDocument doc = ParsedDocument.builder()
                .title("测试文档")
                .content(content)
                .build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSize(4);
        assertThat(segments.get(0).getParentHeading()).isNull();       // 一级
        assertThat(segments.get(1).getParentHeading()).isEqualTo("一级"); // 二级
        assertThat(segments.get(2).getParentHeading()).isEqualTo("二级"); // 三级（父是最近的低层级）
        assertThat(segments.get(3).getParentHeading()).isEqualTo("一级"); // 二级之二（回到一级）
    }

    @Test
    @DisplayName("无标题文档整体为一个 chunk（标题=文档标题）")
    void shouldHandleDocumentWithoutHeadings() {
        String content = "没有标题的纯文本内容，分成多行。\n第二行。\n第三行。";
        ParsedDocument doc = ParsedDocument.builder()
                .title("纯文本文档")
                .content(content)
                .build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).getContent()).contains("没有标题的纯文本内容");
    }

    @Test
    @DisplayName("超长章节按段落切分，子块首块带完整前缀、后续轻量前缀")
    void shouldSplitOversizedSectionWithOverlap() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longBody.append("这是第 ").append(i).append(" 段内容。").append("The quick brown fox jumps. ");
            if (i % 3 == 0) longBody.append("\n\n");
        }
        String content = "# 超长章节\n" + longBody;
        ParsedDocument doc = ParsedDocument.builder()
                .title("测试文档")
                .content(content)
                .build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSizeGreaterThan(1);
        // 首块带完整 [来源:] 前缀
        assertThat(segments.get(0).getContent()).startsWith("[来源: 测试文档 > 超长章节]");
        // 后续子块带轻量 [续: 标题] 前缀（P2-2 修复）
        for (int i = 1; i < segments.size(); i++) {
            assertThat(segments.get(i).getContent()).startsWith("[续: 超长章节]");
        }
        // 每块不超过 maxChunkSize（前缀会略微超，用 ≤ max+30 容忍）
        for (DocumentSegment seg : segments) {
            assertThat(seg.getContent().length()).isLessThanOrEqualTo(530);
        }
        // 子块间有 overlap 衔接（相邻块内容有交集）——P1-2 修复
        for (int i = 0; i < segments.size() - 1; i++) {
            String a = segments.get(i).getContent();
            String b = segments.get(i + 1).getContent();
            assertThat(overlapExists(a, b)).as("块 %d 与 %d 应有内容重叠", i, i + 1).isTrue();
        }
    }

    @Test
    @DisplayName("超长无标点段落不产生超过 maxChunkSize 的块")
    void shouldNotExceedMaxChunkSizeOnNoPunctText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("汉");
        String content = "# 标题\n" + sb;
        ParsedDocument doc = ParsedDocument.builder()
                .title("测试文档")
                .content(content)
                .build();

        List<DocumentSegment> segments = splitter.split(doc);

        assertThat(segments).hasSizeGreaterThan(2);
        for (DocumentSegment seg : segments) {
            assertThat(seg.getContent().length()).isLessThanOrEqualTo(530);
        }
    }

    @Test
    @DisplayName("空内容返回空列表")
    void shouldReturnEmptyForEmptyContent() {
        ParsedDocument doc = ParsedDocument.builder().content("").build();
        assertThat(splitter.split(doc)).isEmpty();
    }

    @Test
    @DisplayName("supports 仅识别含 Markdown 标题的内容")
    void shouldSupportOnlyHeadingContent() {
        assertThat(splitter.supports(ParsedDocument.builder().content("# 标题\n正文").build())).isTrue();
        assertThat(splitter.supports(ParsedDocument.builder().content("无标题文本").build())).isFalse();
    }

    /** 检查两段文本是否有内容级重叠（overlap 生效）。 */
    private boolean overlapExists(String a, String b) {
        String tailA = a.substring(Math.max(0, a.length() - 60));
        String headB = b.substring(0, Math.min(60, b.length()));
        // 简单交集检查：tailA 的片段出现在 headB 中
        for (int i = 0; i <= tailA.length() - 8; i++) {
            if (headB.contains(tailA.substring(i, i + 8))) {
                return true;
            }
        }
        return false;
    }
}
