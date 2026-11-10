package com.yin.ai.platform.knowledge;

import com.yin.ai.platform.knowledge.KnowledgeProperties.KnowledgeBaseConfig;
import com.yin.ai.platform.engine.metrics.AiMetrics;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VectorKnowledgeBase 单元测试 — Mock EmbeddingModel + EmbeddingStore。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VectorKnowledgeBase")
class VectorKnowledgeBaseTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    private KnowledgeBaseConfig config;
    private VectorKnowledgeBase kb;

    @BeforeEach
    void setUp() {
        config = new KnowledgeBaseConfig();
        config.setType("vector");
        config.setMaxDocuments(5);
        config.setRerankThreshold(0.7);
        config.setCandidateMultiplier(3);

        AiMetrics aiMetrics = new AiMetrics(new SimpleMeterRegistry());

        kb = new VectorKnowledgeBase("test-kb", embeddingStore, embeddingModel,
                config, 3000, aiMetrics);
    }

    @Test
    @DisplayName("null/空查询返回空")
    void shouldReturnEmptyForNullOrBlankQuery() {
        assertThat(kb.retrieve(null)).isEmpty();
        assertThat(kb.retrieve("")).isEmpty();
        assertThat(kb.retrieve("   ")).isEmpty();
    }

    @Test
    @DisplayName("检索成功返回组装好的 Markdown 上下文")
    void shouldRetrieveAndAssembleContext() {
        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f});
        @SuppressWarnings("unchecked")
        Response<Embedding> mockResponse = mock(Response.class);
        when(mockResponse.content()).thenReturn(mockEmbedding);
        when(embeddingModel.embed(any(String.class))).thenReturn(mockResponse);

        dev.langchain4j.data.document.Metadata meta1 = new dev.langchain4j.data.document.Metadata();
        meta1.put("title", "标题1");
        TextSegment seg1 = TextSegment.from("文档内容1", meta1);

        dev.langchain4j.data.document.Metadata meta2 = new dev.langchain4j.data.document.Metadata();
        meta2.put("title", "标题2");
        TextSegment seg2 = TextSegment.from("文档内容2", meta2);

        EmbeddingMatch<TextSegment> match1 = mockMatch(seg1, "id1", 0.95);
        EmbeddingMatch<TextSegment> match2 = mockMatch(seg2, "id2", 0.85);

        @SuppressWarnings("unchecked")
        EmbeddingSearchResult<TextSegment> searchResult = mock(EmbeddingSearchResult.class);
        when(searchResult.matches()).thenReturn(List.of(match1, match2));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(searchResult);

        String context = kb.retrieve("测试查询");
        System.out.println(context);

        assertThat(context).isNotEmpty();
        assertThat(context).contains("## 知识库: test-kb");
        assertThat(context).contains("文档1: 标题1");
        assertThat(context).contains("文档2: 标题2");
        assertThat(context).contains("95%");
        assertThat(context).contains("85%");
    }

    @Test
    @DisplayName("检索无结果时返回空")
    void shouldReturnEmptyForNoResults() {
        Response<Embedding> emptyResponse = mock(Response.class);
        when(emptyResponse.content()).thenReturn(new Embedding(new float[]{0.1f}));
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(emptyResponse);

        EmbeddingSearchResult<TextSegment> result = mock(EmbeddingSearchResult.class);
        when(result.matches()).thenReturn(List.of());
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        assertThat(kb.retrieve("无结果查询")).isEmpty();
    }

    @Test
    @DisplayName("EmbeddingModel 异常时返回空（降级）")
    void shouldReturnEmptyOnEmbeddingError() {
        when(embeddingModel.embed(any(String.class)))
                .thenThrow(new RuntimeException("Embedding service unavailable"));

        String context = kb.retrieve("查询");
        assertThat(context).isEmpty();
    }

    @Test
    @DisplayName("getName 返回构造时传入的名称")
    void shouldReturnName() {
        assertThat(kb.getName()).isEqualTo("test-kb");
    }

    @SuppressWarnings("unchecked")
    private EmbeddingMatch<TextSegment> mockMatch(TextSegment segment, String id, double score) {
        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(match.embedded()).thenReturn(segment);
        when(match.embeddingId()).thenReturn(id);
        when(match.score()).thenReturn(score);
        return match;
    }
}
