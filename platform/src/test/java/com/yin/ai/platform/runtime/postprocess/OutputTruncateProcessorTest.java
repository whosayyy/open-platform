package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputTruncateProcessor")
class OutputTruncateProcessorTest {

    private final OutputTruncateProcessor processor = new OutputTruncateProcessor(50);

    @Test
    @DisplayName("文本长度小于 maxChars 时原样返回")
    void shouldReturnUnchangedWhenShorterThanMax() {
        String input = "短文本";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("文本超过 maxChars 时在句子边界截断")
    void shouldTruncateAtSentenceBoundary() {
        String input = "这是一段很长的测试文本内容用来验证截断功能。" +
                "这里是第二句话用来填充更多内容！" +
                "还有第三句问句？" +
                "后面是超出的部分需要被删掉的内容继续写一些。";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result.length()).isLessThanOrEqualTo(50);
        assertThat(result).endsWith("？");
        assertThat(result).doesNotContain("超出的部分");
    }

    @Test
    @DisplayName("无句子边界时在 maxChars 处硬截断")
    void shouldHardTruncateWhenNoSentenceBoundary() {
        String input = "这是一段没有标点符号的连续文本内容需要被截断处理还需要更多字符来超越限制";
        String result = processor.process(input, SkillExecutionContext.builder().build());
        assertThat(result.length()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("空文本原样返回")
    void shouldReturnNullAndBlankUnchanged() {
        assertThat(processor.process(null, SkillExecutionContext.builder().build())).isNull();
        assertThat(processor.process("", SkillExecutionContext.builder().build())).isEmpty();
    }

    @Test
    @DisplayName("order 为 30")
    void shouldReturnOrder30() {
        assertThat(processor.order()).isEqualTo(30);
    }
}
