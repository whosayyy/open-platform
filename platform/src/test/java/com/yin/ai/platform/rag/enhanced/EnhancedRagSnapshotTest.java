package com.yin.ai.platform.rag.enhanced;

import com.yin.ai.platform.agent.trace.StepTraceRecorder;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.yin.ai.platform.core.rag.RetrievedDocument;

class EnhancedRagSnapshotTest {

    private VectorRetriever vectorRetriever;
    private EnhancedRagService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        vectorRetriever = mock(VectorRetriever.class);
        Bm25Retriever bm25Retriever = mock(Bm25Retriever.class);
        LlmReranker reranker = mock(LlmReranker.class);
        EnhancedRagProperties properties = new EnhancedRagProperties();
        properties.getQueryRewrite().setEnabled(false);
        properties.getMultiQuery().setEnabled(false);
        properties.getBm25().setEnabled(false);
        properties.getRerank().setEnabled(false);

        ObjectProvider<RetrievalTraceRecorder> traceProvider = mock(ObjectProvider.class);
        ObjectProvider<OnlineQualityRecorder> qualityProvider = mock(ObjectProvider.class);
        ObjectProvider<StepTraceRecorder> stepProvider = mock(ObjectProvider.class);
        when(traceProvider.getIfAvailable()).thenReturn(null);
        when(qualityProvider.getIfAvailable()).thenReturn(null);
        when(stepProvider.getIfAvailable()).thenReturn(null);

        service = new EnhancedRagService(rewriter, vectorRetriever, bm25Retriever, reranker,
                properties, traceProvider, qualityProvider, stepProvider);
    }

    @Test
    void snapshotKeepsContextAndReferencesFromSameRetrieval() {
        RetrievedDocument document = new RetrievedDocument(
                "doc-1", "企业知识", "经过审核的事实", 0.91, "vector", Map.of());
        when(vectorRetriever.retrieve(any(), anyInt(), anyDouble(), isNull()))
                .thenReturn(List.of(document));

        RagRetrievalSnapshot snapshot = service.retrieveSnapshot("企业问题", null);

        assertThat(snapshot.documents()).extracting(RetrievedDocument::docId)
                .containsExactly("doc-1");
        assertThat(snapshot.context()).contains("[E1]", "经过审核的事实", "doc-1");
        assertThat(snapshot.evidenceSufficient()).isTrue();
        assertThat(snapshot.traceId()).isNotBlank();
        verify(vectorRetriever).retrieve(any(), anyInt(), anyDouble(), isNull());
    }

    @Test
    void complexQueryExpandsTopKWithinConfiguredMaximum() {
        assertThat(service.resolveTopK("请分别比较两个方案的优缺点以及长期影响，并给出风险分析、实施边界、依赖条件和回滚策略"))
                .isEqualTo(9);
        assertThat(service.resolveTopK("简单问题")).isEqualTo(5);
    }
}
