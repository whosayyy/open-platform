package com.yin.ai.platform.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenCounter")
class TokenCounterTest {

    @Test
    @DisplayName("空字符串返回 0")
    void emptyString() {
        assertThat(TokenCounter.estimate("")).isEqualTo(0);
    }

    @Test
    @DisplayName("null 返回 0")
    void nullString() {
        assertThat(TokenCounter.estimate(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("短英文文本按 2.5 chars/token 估算")
    void shortEnglishText() {
        int tokens = TokenCounter.estimate("Hello world");
        assertThat(tokens).isEqualTo(5); // 11 chars / 2.5 = 4.4 → ceil = 5
    }

    @Test
    @DisplayName("中文文本按 2.5 chars/token 估算")
    void chineseText() {
        int tokens = TokenCounter.estimate("你好世界");
        assertThat(tokens).isEqualTo(2); // 4 chars / 2.5 = 1.6 → ceil = 2
    }

    @Test
    @DisplayName("混合中英文估算")
    void mixedText() {
        int tokens = TokenCounter.estimate("Hello 你好 World 世界");
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    @DisplayName("长文本 token 数随长度增长")
    void longTextGrowsWithLength() {
        int short_ = TokenCounter.estimate("Hello");
        int long_ = TokenCounter.estimate("Hello world this is a much longer text");
        assertThat(long_).isGreaterThan(short_);
    }

    @Test
    @DisplayName("estimateTotal 空列表返回 0")
    void estimateTotalEmptyList() {
        assertThat(TokenCounter.estimateTotal(List.of())).isEqualTo(0);
    }

    @Test
    @DisplayName("estimateTotal 计算多条消息")
    void estimateTotalMultipleMessages() {
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "Hello"),
                new SessionMemoryManager.ChatMessage("assistant", "Hi there!")
        );
        int total = TokenCounter.estimateTotal(messages);
        assertThat(total).isGreaterThan(0);
    }
}
