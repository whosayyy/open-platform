package com.yin.ai.platform.adapter.rag;

import com.yin.ai.platform.core.retrieval.RetrievalQuery;
import com.yin.ai.platform.core.retrieval.RetrievalResult;
import com.yin.ai.platform.core.retrieval.RetrievedDocument;
import com.yin.ai.platform.core.retrieval.Retriever;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exposes a provider-neutral Retriever as a read-only core Tool. */
public final class RetrieverToolAdapter implements Tool {

    public static final String DEFAULT_NAME = "rag_search";
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 50;

    private final Retriever retriever;
    private final ToolDefinition definition;
    private final int defaultLimit;

    public RetrieverToolAdapter(Retriever retriever) {
        this(retriever, DEFAULT_NAME, "Retrieve grounded evidence from a knowledge source",
                DEFAULT_LIMIT);
    }

    public RetrieverToolAdapter(Retriever retriever, String name, String description,
                                int defaultLimit) {
        if (retriever == null) throw new IllegalArgumentException("retriever must not be null");
        this.retriever = retriever;
        this.defaultLimit = boundedLimit(defaultLimit, DEFAULT_LIMIT);
        this.definition = new ToolDefinition(name, description, "1.0.0", inputSchema(),
                Set.of("rag", "retrieval"), ToolDefinition.Risk.READ_ONLY);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> parameters = input == null ? Map.of() : input.parameters();
        String query = firstText(parameters.get("query"), parameters.get("text"),
                parameters.get("keywords"));
        if (query == null) {
            return ToolResult.failure("invalid_retrieval_query",
                    "query, text, or keywords must contain a value");
        }
        int limit = boundedLimit(parameters.get("limit"), defaultLimit);
        Map<String, Object> filters = objectMap(parameters.get("filters"));
        try {
            RetrievalResult result = retriever.retrieve(new RetrievalQuery(query, limit, filters),
                    input == null ? null : input.executionContext());
            if (result == null) {
                return ToolResult.failure("retrieval_unavailable", "retriever returned no result");
            }
            List<RetrievedDocument> evidence = result.documents().stream()
                    .filter(document -> document != null && !document.content().isBlank())
                    .toList();
            if (evidence.isEmpty()) {
                return ToolResult.failure("retrieval_no_evidence",
                        "retriever returned no evidence documents");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
            metadata.put("documentCount", evidence.size());
            metadata.put("query", query);
            return new ToolResult(true, formatEvidence(evidence), null, null, metadata);
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ToolResult.failure("retrieval_failed", safeMessage(exception));
        }
    }

    private static String formatEvidence(List<RetrievedDocument> documents) {
        if (documents == null || documents.isEmpty()) return "No retrieval evidence found.";
        StringBuilder result = new StringBuilder();
        int evidenceIndex = 1;
        for (RetrievedDocument document : documents) {
            if (document == null || document.content().isBlank()) continue;
            Map<String, Object> metadata = document.metadata();
            String title = firstText(metadata.get("title"), document.id());
            String url = firstText(metadata.get("url"), metadata.get("sourceUrl"),
                    metadata.get("link"));
            String source = firstText(metadata.get("source"), metadata.get("provider"));
            result.append("[E").append(evidenceIndex++).append("] ")
                    .append(title == null ? "Retrieved evidence" : title).append('\n');
            if (url != null) result.append("URL: ").append(url).append('\n');
            if (source != null) result.append("Source: ").append(source).append('\n');
            result.append("Score: ").append(document.score()).append('\n')
                    .append(document.content().trim()).append("\n\n");
        }
        return result.length() == 0 ? "No retrieval evidence found." : result.toString().trim();
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string",
                "description", "Knowledge retrieval query"));
        properties.put("limit", Map.of("type", "integer", "minimum", 1,
                "maximum", MAX_LIMIT, "default", DEFAULT_LIMIT));
        properties.put("filters", Map.of("type", "object",
                "description", "Provider-neutral metadata filters"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("query"));
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) result.put(String.valueOf(key), value);
        });
        return Map.copyOf(result);
    }

    private static int boundedLimit(Object raw, int fallback) {
        int value = fallback;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw != null) {
            try {
                value = Integer.parseInt(raw.toString());
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
