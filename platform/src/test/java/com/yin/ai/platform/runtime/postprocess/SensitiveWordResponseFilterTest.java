package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SensitiveWordResponseFilter")
class SensitiveWordResponseFilterTest {

    private SensitiveWordResponseFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SensitiveWordResponseFilter();
    }

    @Test
    @DisplayName("包含敏感词的文本被脱敏")
    void shouldMaskSensitiveWord() {
        String input = "这是一段包含台独的内容";
        String result = filter.process(input, SkillExecutionContext.builder().build());
        assertThat(result).doesNotContain("台独");
        assertThat(result).contains("**");
    }

    @Test
    @DisplayName("无敏感词的文本原样返回")
    void shouldReturnUnchangedForCleanText() {
        String input = "这是一段正常的内容";
        String result = filter.process(input, SkillExecutionContext.builder().build());
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("空文本原样返回")
    void shouldReturnNullAndBlankUnchanged() {
        assertThat(filter.process(null, SkillExecutionContext.builder().build())).isNull();
        assertThat(filter.process("", SkillExecutionContext.builder().build())).isEmpty();
        assertThat(filter.process("   ", SkillExecutionContext.builder().build())).isEqualTo("   ");
    }

    @Test
    @DisplayName("order 为 10")
    void shouldReturnOrder10() {
        assertThat(filter.order()).isEqualTo(10);
    }
}
