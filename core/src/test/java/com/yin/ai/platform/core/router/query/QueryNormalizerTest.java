package com.yin.ai.platform.core.router.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryNormalizer 单元测试 — 纯函数，无需 Mock。
 */
@DisplayName("QueryNormalizer")
class QueryNormalizerTest {

    private static final java.util.Map<String, String> TEST_SYNONYMS = java.util.Map.ofEntries(
            java.util.Map.entry("帮我总结一下", "总结"),
            java.util.Map.entry("帮我总结", "总结"),
            java.util.Map.entry("给我总结", "总结"),
            java.util.Map.entry("帮我概括", "总结"),
            java.util.Map.entry("帮我审核", "审核"),
            java.util.Map.entry("帮我搜索", "搜索"),
            java.util.Map.entry("帮我查", "搜索"),
            java.util.Map.entry("帮我找", "搜索"),
            java.util.Map.entry("查一下", "搜索"),
            java.util.Map.entry("帮我翻译", "翻译"),
            java.util.Map.entry("翻译一下", "翻译"),
            java.util.Map.entry("帮我看看", "看一下"));

    private QueryNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new QueryNormalizer(TEST_SYNONYMS);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null 或空输入返回空字符串")
    void shouldReturnEmptyForNullOrBlank(String input) {
        assertThat(normalizer.normalize(input)).isEmpty();
    }

    @Test
    @DisplayName("trim 首尾空白")
    void shouldTrimWhitespace() {
        assertThat(normalizer.normalize("  今天有什么新闻  ")).isEqualTo("今天有什么新闻");
    }

    @Test
    @DisplayName("全角 ASCII 字符转半角")
    void shouldConvertFullWidthToHalfWidth() {
        assertThat(normalizer.normalize("ＡＢＣａｂｃ１２３"))
                .isEqualTo("abcabc123");
    }

    @Test
    @DisplayName("全角空格转半角空格")
    void shouldConvertFullWidthSpace() {
        assertThat(normalizer.normalize("今天　有什么　新闻"))
                .isEqualTo("今天 有什么 新闻");
    }

    @Test
    @DisplayName("英文大写转小写")
    void shouldLowerCaseEnglish() {
        assertThat(normalizer.normalize("Hello World NEWS"))
                .isEqualTo("hello world news");
    }

    @Test
    @DisplayName("连续空白压缩为单个空格")
    void shouldCollapseWhitespace() {
        assertThat(normalizer.normalize("今天   有什么\r\n\t新闻"))
                .isEqualTo("今天 有什么 新闻");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
           帮我总结一下              | 总结
           帮我总结                  | 总结
           给我总结                  | 总结
           帮我概括                  | 总结
           帮我审核                  | 审核
           帮我搜索                  | 搜索
           帮我查                    | 搜索
           帮我找                    | 搜索
           查一下                    | 搜索
           帮我翻译                  | 翻译
           翻译一下                  | 翻译
           帮我看看                  | 看一下
           """)
    @DisplayName("同义词归一化")
    void shouldApplySynonyms(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("业务同义词由应用注入")
    void shouldApplyApplicationOwnedSynonyms() {
        QueryNormalizer applicationNormalizer = new QueryNormalizer(
                java.util.Map.of("今天的热点", "热点"));
        assertThat(applicationNormalizer.normalize("今天的热点")).isEqualTo("热点");
        assertThat(new QueryNormalizer().normalize("今天的热点")).isEqualTo("今天的热点");
    }

    @Test
    @DisplayName("长同义词优先匹配（长短语优先于短词）")
    void shouldMatchLongerSynonymFirst() {
        assertThat(normalizer.normalize("帮我总结一下这篇文章"))
                .isEqualTo("总结这篇文章");
    }

    @Test
    @DisplayName("全半角混合 + 同义词 + 空白压缩组合场景")
    void shouldHandleCombinedTransformations() {
        String input = "　帮我总结一下　　Hello　World　";
        assertThat(normalizer.normalize(input))
                .isEqualTo("总结 hello world");
    }

    @Test
    @DisplayName("中文内容不变")
    void shouldPreserveChineseCharacters() {
        assertThat(normalizer.normalize("今天有什么新闻"))
                .isEqualTo("今天有什么新闻");
    }

    @Test
    @DisplayName("特殊字符保留")
    void shouldPreserveSpecialCharacters() {
        assertThat(normalizer.normalize("价格: $100 和 €50"))
                .isEqualTo("价格: $100 和 €50");
    }
}
