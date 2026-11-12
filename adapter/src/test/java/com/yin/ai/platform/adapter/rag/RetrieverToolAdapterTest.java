package com.yin.ai.platform.adapter.rag;

import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.retrieval.RetrievalQuery;
import com.yin.ai.platform.core.retrieval.RetrievalResult;
import com.yin.ai.platform.core.retrieval.RetrievedDocument;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverToolAdapterTest {

    @Test
    void convertsRetrieverResultsIntoGroundedToolEvidence() {
        AtomicReference<RetrievalQuery> capturedQuery = new AtomicReference<>();
        AtomicReference<ExecutionContext> capturedContext = new AtomicReference<>();
        RetrieverToolAdapter adapter = new RetrieverToolAdapter((query, context) -> {
            capturedQuery.set(query);
            capturedContext.set(context);
            return new RetrievalResult(List.of(new RetrievedDocument("doc-1",
                    "Grounded content", 0.91,
                    Map.of("title", "Official report",
                            "url", "https://example.com/report",
                            "source", "Example"))), Map.of("route", "hybrid"));
        });
        ExecutionContext context = ExecutionContext.create("rag-trace");

        ToolResult result = adapter.execute(new ToolInput(Map.of(
                "query", "AI regulation", "limit", 5,
                "filters", Map.of("tenant", "t-1")), "request-1", context));

        assertThat(result.success()).isTrue();
        assertThat(result.data().toString())
                .contains("[E1] Official report", "https://example.com/report",
                        "Grounded content");
        assertThat(result.metadata())
                .containsEntry("documentCount", 1)
                .containsEntry("route", "hybrid");
        assertThat(capturedQuery.get().text()).isEqualTo("AI regulation");
        assertThat(capturedQuery.get().limit()).isEqualTo(5);
        assertThat(capturedQuery.get().filters()).containsEntry("tenant", "t-1");
        assertThat(capturedContext.get()).isSameAs(context);
        assertThat(adapter.definition().risk()).isEqualTo(ToolDefinition.Risk.READ_ONLY);
    }

    @Test
    void rejectsBlankQueriesBeforeCallingTheRetriever() {
        RetrieverToolAdapter adapter = new RetrieverToolAdapter((query, context) -> {
            throw new AssertionError("Retriever should not be called");
        });

        ToolResult result = adapter.execute(new ToolInput(Map.of(), "request-1", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("invalid_retrieval_query");
    }

    @Test
    void failsClosedWhenTheRetrieverReturnsNoEvidence() {
        RetrieverToolAdapter adapter = new RetrieverToolAdapter((query, context) ->
                new RetrievalResult(List.of(new RetrievedDocument("empty", " ", 0.1,
                        Map.of())), Map.of()));

        ToolResult result = adapter.execute(new ToolInput(
                Map.of("query", "missing topic"), "request-1", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("retrieval_no_evidence");
    }
}
