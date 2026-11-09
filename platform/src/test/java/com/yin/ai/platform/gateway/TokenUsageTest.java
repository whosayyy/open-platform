package com.yin.ai.platform.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenUsage — 计费 Token 计算")
class TokenUsageTest {

    @Test
    @DisplayName("无缓存命中时，计费 prompt tokens = 原始 prompt tokens")
    void billablePromptTokensWithoutCache() {
        TokenUsage usage = new TokenUsage(1000, 500, 1500);

        assertThat(usage.getBillablePromptTokens()).isEqualTo(1000);
        assertThat(usage.isCacheHit()).isFalse();
    }

    @Test
    @DisplayName("缓存命中时，计费 prompt tokens 按 10% 折扣计算缓存部分")
    void billablePromptTokensWithCacheHit() {
        TokenUsage usage = new TokenUsage(1000, 500, 1500);
        usage.setCachedPromptTokens(800);
        usage.setCacheHit(true);

        // 800 cached → ceil(800 * 0.1) = 80
        // 200 non-cached → 200
        // total = 280
        assertThat(usage.getBillablePromptTokens()).isEqualTo(280);
    }

    @Test
    @DisplayName("全部缓存命中时，仅收取 10% 计费")
    void billablePromptTokensAllCached() {
        TokenUsage usage = new TokenUsage(1000, 500, 1500);
        usage.setCachedPromptTokens(1000);
        usage.setCacheHit(true);

        assertThat(usage.getBillablePromptTokens()).isEqualTo(100); // ceil(1000 * 0.1)
    }

    @Test
    @DisplayName("promptTokens 为 null 时返回 0")
    void billablePromptTokensWhenNull() {
        TokenUsage usage = new TokenUsage(null, 500, 500);

        assertThat(usage.getBillablePromptTokens()).isEqualTo(0);
    }

    @Test
    @DisplayName("cachedPromptTokens 为 null 时按正常计费")
    void billablePromptTokensWhenCachedNull() {
        TokenUsage usage = new TokenUsage(500, 300, 800);
        usage.setCachedPromptTokens(null);

        assertThat(usage.getBillablePromptTokens()).isEqualTo(500);
    }
}
