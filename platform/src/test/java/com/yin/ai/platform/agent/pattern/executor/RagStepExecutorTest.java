package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.rag.enhanced.EnhancedRagService;
import com.yin.ai.platform.rag.enhanced.RagRetrievalSnapshot;
import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagStepExecutorTest {

    private final EnhancedRagService enhancedRagService = mock(EnhancedRagService.class);
    private final AgentExecutionContext ctx = mock(AgentExecutionContext.class);
    private RagStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RagStepExecutor(enhancedRagService);
        when(ctx.getGoal()).thenReturn("本周新闻热点分析");
        when(ctx.getVariable("userId")).thenReturn(7L);
        when(ctx.getCurrentStepId()).thenReturn("step-rag");
    }

    @Test
    void executorPrefixIsRagSearch() {
        assertThat(executor.executorPrefix()).isEqualTo("rag_search");
    }

    @Test
    void executeFormatsDocsIntoMarkdownContext() {
        List<RetrievedDocument> documents = List.of(
                new RetrievedDocument("doc-1", "新闻标题一", "内容一", 9.0, "rerank", Map.of()),
                new RetrievedDocument("doc-2", "新闻标题二", "内容二", 8.0, "rerank", Map.of()));
        when(enhancedRagService.retrieveSnapshot("本周新闻热点分析", 7L)).thenReturn(
                snapshot("本周新闻热点分析", documents,
                        "## 可引用证据\n\n### [E1] 新闻标题一\n\n内容一\n\n来源标识: doc-1"));

        StepExecutorResult result = executor.execute("rag_search", Map.of(), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("可引用证据");
        assertThat(result.output()).contains("新闻标题一");
        assertThat(result.output()).contains("内容一");
        assertThat(result.output()).contains("[E1]");
        verify(enhancedRagService).retrieveSnapshot(eq("本周新闻热点分析"), eq(7L));
    }

    @Test
    void executeUsesInputQueryOverGoal() {
        List<RetrievedDocument> documents = List.of(
                new RetrievedDocument("doc-1", "t", "c", 8.0, "rerank", Map.of()));
        when(enhancedRagService.retrieveSnapshot("显式查询", 7L)).thenReturn(
                snapshot("显式查询", documents, "### [E1] t\n\nc"));

        StepExecutorResult result = executor.execute("rag_search",
                Map.of("query", "显式查询"), ctx);

        assertThat(result.success()).isTrue();
        verify(enhancedRagService).retrieveSnapshot(eq("显式查询"), eq(7L));
    }

    @Test
    void executeFailsClosedWhenNoDocs() {
        when(enhancedRagService.retrieveSnapshot(anyString(), eq(7L))).thenReturn(
                RagRetrievalSnapshot.empty("trace-empty", "本周新闻热点分析",
                        "no_retrieval_results", 2));

        StepExecutorResult result = executor.execute("rag_search", Map.of(), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("evidence insufficient", "no_retrieval_results");
    }

    @Test
    void executeFailsOnRetrievalException() {
        when(enhancedRagService.retrieveSnapshot(anyString(), eq(7L)))
                .thenThrow(new IllegalStateException("es down"));

        StepExecutorResult result = executor.execute("rag_search", Map.of(), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("es down");
    }

    private RagRetrievalSnapshot snapshot(String query, List<RetrievedDocument> documents,
                                          String context) {
        return new RagRetrievalSnapshot("trace-rag", query, query, documents, context,
                true, "", 3);
    }
}
