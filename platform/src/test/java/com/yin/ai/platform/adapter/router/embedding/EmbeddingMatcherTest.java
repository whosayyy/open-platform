package com.yin.ai.platform.adapter.router.embedding;

import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.RouteContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EmbeddingMatcher")
class EmbeddingMatcherTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingMatcher matcher;
    private RouteContext ctx;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return mockEmbeddingResponse(deterministicVector(text));
        });

        Map<String, String> descriptions = Map.of(
                "summary", "总结概括摘要生成文章内容提炼",
                "review", "审核审阅检查内容合规性",
                "search", "搜索查找检索信息",
                "chat", "对话聊天交流讨论"
        );
        matcher = new EmbeddingMatcher(embeddingModel, 0.7, descriptions);
        matcher.init(); // manually invoke PostConstruct
        ctx = RouteContext.builder().build();
    }

    @Test
    @DisplayName("priority 为 40")
    void shouldHavePriority40() {
        assertThat(matcher.priority()).isEqualTo(40);
    }

    @Test
    @DisplayName("name 为 embedding")
    void shouldBeNamedEmbedding() {
        assertThat(matcher.name()).isEqualTo("embedding");
    }

    @Test
    @DisplayName("输入与意图描述高度相似时匹配成功")
    void shouldMatchWhenInputMatchesDescription() {
        // Use intent description text itself — guaranteed high similarity
        String input = "总结概括摘要生成文章内容提炼";
        Optional<IntentMatchResult> result = matcher.match(input, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().getIntent()).isEqualTo("summary");
        assertThat(result.get().getConfidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(result.get().getMatchedBy()).isEqualTo("embedding");
    }

    @Test
    @DisplayName("无意义英文输入不匹配任何意图")
    void shouldReturnEmptyForUnrelatedInput() {
        // Pure ASCII with no Chinese semantic overlap
        String input = "hello world foo bar baz qux quux";
        Optional<IntentMatchResult> result = matcher.match(input, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null EmbeddingModel 时降级返回 empty")
    void shouldDegradeWhenModelNull() {
        EmbeddingMatcher nullMatcher = new EmbeddingMatcher(null);
        nullMatcher.init();
        Optional<IntentMatchResult> result = nullMatcher.match("test", ctx);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("空输入返回 empty")
    void shouldReturnEmptyForBlankInput() {
        assertThat(matcher.match(null, ctx)).isEmpty();
        assertThat(matcher.match("", ctx)).isEmpty();
        assertThat(matcher.match("   ", ctx)).isEmpty();
    }

    @Test
    @DisplayName("Cosine similarity 计算正确")
    void shouldComputeCosineSimilarity() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        assertThat(EmbeddingMatcher.cosineSimilarity(a, b)).isEqualTo(0.0);

        float[] c = {1.0f, 2.0f, 3.0f};
        assertThat(EmbeddingMatcher.cosineSimilarity(c, c)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    // ——— helpers ———

    private static Response<Embedding> mockEmbeddingResponse(float[] vector) {
        Embedding embedding = mock(Embedding.class);
        when(embedding.vector()).thenReturn(vector);
        @SuppressWarnings("unchecked")
        Response<Embedding> response = mock(Response.class);
        when(response.content()).thenReturn(embedding);
        return response;
    }

    /**
     * Deterministic pseudo-random vector from text content.
     * Uses character distribution across the text to produce distinctive vectors.
     * Related texts produce closer vectors than unrelated ones.
     */
    private static float[] deterministicVector(String text) {
        float[] vec = new float[16];
        if (text == null || text.isEmpty()) return vec;
        String lower = text.toLowerCase();

        // Use character n-gram counting for diversity
        for (int i = 0; i < lower.length(); i++) {
            int ch = lower.charAt(i);
            // Distribute character contributions across vec components
            for (int d = 0; d < vec.length; d++) {
                vec[d] += Math.sin(ch * (d + 1) * 0.7) * 0.1f;
            }
            // Bigram influence
            if (i + 1 < lower.length()) {
                int bg = (lower.charAt(i) << 8) | lower.charAt(i + 1);
                for (int d = 0; d < vec.length; d++) {
                    vec[d] += Math.cos(bg * (d + 1) * 0.3) * 0.05f;
                }
            }
        }

        // Normalize to unit length
        double norm = 0.0;
        for (float v : vec) norm += v * v;
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vec.length; i++) vec[i] *= scale;
        }
        return vec;
    }
}
