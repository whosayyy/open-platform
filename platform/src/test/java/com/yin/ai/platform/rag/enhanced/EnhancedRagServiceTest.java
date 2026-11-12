package com.yin.ai.platform.rag.enhanced;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.yin.ai.platform.core.rag.RetrievedDocument;

/**
 * 【BUG 修复回归测试】EnhancedRagService.cleanRagQuery 剥离系统注入的时间上下文前缀。
 * <p>
 * 根因：goal 带【当前时间…】+ 编号要求前缀 → 被 ReActLoop 当作 RAG query → QueryRewriter
 * 拿到混杂文本 → LLM 拒识/超长 → 检索空结果。净化后只留用户真实意图。
 */
class EnhancedRagServiceTest {

    @Test
    void stripsTimeContextPrefix() {
        String goal = "【当前时间：2026年8月6日 21:05 (星期四)，今日日期：2026-08-06】\n"
                + "【时效性硬性要求（必须遵守）】\n"
                + "1. 你只能使用与当前时间相符的最新数据。\n"
                + "2. 严禁使用你训练数据里的旧信息。\n"
                + "帮我写一篇关于DeepSeek的分析文章";

        String cleaned = EnhancedRagService.cleanRagQuery(goal);

        assertThat(cleaned).isEqualTo("帮我写一篇关于DeepSeek的分析文章");
    }

    @Test
    void plainQueryUntouched() {
        String query = "DeepSeek 最新技术进展";
        assertThat(EnhancedRagService.cleanRagQuery(query)).isEqualTo(query);
    }

    @Test
    void nullOrBlankReturnsAsIs() {
        assertThat(EnhancedRagService.cleanRagQuery(null)).isNull();
        assertThat(EnhancedRagService.cleanRagQuery("   ")).isEqualTo("   ");
    }

    @Test
    void multiLineUserIntentJoined() {
        String goal = "【当前时间：2026年8月7日 09:00 (星期五)，今日日期：2026-08-07】\n"
                + "帮我写一篇关于\nDeepSeek 的文章";

        String cleaned = EnhancedRagService.cleanRagQuery(goal);

        assertThat(cleaned).isEqualTo("帮我写一篇关于 DeepSeek 的文章");
    }

    @Test
    void removesDocumentsRejectedByReranker() {
        List<RetrievedDocument> filtered = EnhancedRagService.filterLowConfidence(List.of(
                new RetrievedDocument("relevant", "勇士阵容", "content", 8.0, "rerank", Map.of()),
                new RetrievedDocument("noise", "无关内容", "content", 0.0, "rerank", Map.of()),
                new RetrievedDocument("vector", "向量兜底", "content", 0.82, "vector", Map.of())
        ), 3.0);

        assertThat(filtered).extracting(RetrievedDocument::docId)
                .containsExactly("relevant", "vector");
    }
}
