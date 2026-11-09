package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownFormatProcessor")
class MarkdownFormatProcessorTest {

    private MarkdownFormatProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MarkdownFormatProcessor();
    }

    @Test
    @DisplayName("移除孤立的无效 heading")
    void shouldRemoveLonelyHash() {
        String input = "#\n\n正常内容\n##\n\nmore content";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result).doesNotContainPattern("(?m)^#\\s*$");
        assertThat(result).doesNotContainPattern("(?m)^##\\s*$");
        assertThat(result).contains("正常内容");
    }

    @Test
    @DisplayName("heading 后无空行时添加空行")
    void shouldAddBlankLineAfterHeading() {
        String input = "## 标题\n直接内容";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result).contains("## 标题\n\n直接内容");
    }

    @Test
    @DisplayName("3 个以上连续换行合并为两个")
    void shouldCollapseExcessiveNewlines() {
        String input = "段落一\n\n\n\n段落二\n\n\n段落三";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result).doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("正常 Markdown 不变")
    void shouldLeaveWellFormedMarkdownUnchanged() {
        String input = "## 标题\n\n内容段落\n\n### 子标题\n\n更多内容";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("空文本原样返回")
    void shouldReturnNullAndBlankUnchanged() {
        assertThat(processor.process(null, SkillExecutionContext.builder().build())).isNull();
        assertThat(processor.process("", SkillExecutionContext.builder().build())).isEmpty();
    }

    @Test
    @DisplayName("order 为 20")
    void shouldReturnOrder20() {
        assertThat(processor.order()).isEqualTo(20);
    }
}
