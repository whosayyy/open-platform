package com.yin.examples.codeqa;

import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 一个极简的代码片段知识库，供 {@link CodeSearchTool} 检索。
 * <p>
 * 这里刻意用内存实现：示例的重点是「消费方如何接入平台」，
 * 不是「如何做检索」。真实项目应当把这里换成 RAG / 向量库。
 */
public final class SnippetStore {

    /** 一条代码片段。 */
    public record Snippet(String id, String title, String keywords, String body) {

        boolean matches(String normalizedQuery) {
            if (keywords == null) {
                return false;
            }
            for (String keyword : keywords.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
                if (!keyword.isBlank() && normalizedQuery.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Snippet> snippets = new ArrayList<>();

    public SnippetStore add(String id, String title, String keywords, String body) {
        snippets.add(new Snippet(id, title, keywords, body));
        return this;
    }

    /** 按关键词做朴素匹配；无命中时返回空，让调用方决定如何降级。 */
    public List<Snippet> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        List<Snippet> hits = new ArrayList<>();
        for (Snippet snippet : snippets) {
            if (snippet.matches(normalized)) {
                hits.add(snippet);
                if (hits.size() >= limit) {
                    break;
                }
            }
        }
        return hits;
    }

    public int size() {
        return snippets.size();
    }

    /** 暴露给模型的工具：按关键词检索代码片段。 */
    public Tool asTool() {
        return new CodeSearchTool(this);
    }

    /** 把命中片段拼成便于模型阅读的文本。 */
    static String render(List<Snippet> hits) {
        if (hits.isEmpty()) {
            return "NO_MATCH: the codebase has no snippet matching this query.";
        }
        StringBuilder builder = new StringBuilder();
        for (Snippet snippet : hits) {
            builder.append("### ").append(snippet.title())
                    .append(" (").append(snippet.id()).append(")\n")
                    .append(snippet.body()).append("\n\n");
        }
        return builder.toString().trim();
    }

    static Optional<Snippet> find(List<Snippet> hits, String id) {
        return hits.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** 供测试与调试使用：直接按 id 取片段。 */
    public Optional<Snippet> byId(String id) {
        return snippets.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** 命中数量，便于示例输出统计。 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("snippetCount", snippets.size());
        return stats;
    }

    /** 工具实现：只依赖平台的 Tool SPI，不依赖任何框架。 */
    static final class CodeSearchTool implements Tool {

        private static final int DEFAULT_LIMIT = 3;

        private final SnippetStore store;

        CodeSearchTool(SnippetStore store) {
            this.store = store;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(
                    "search_code",
                    "Search the local code snippet knowledge base by keyword. "
                            + "Use this before answering questions about this codebase.",
                    "1.0.0",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "query", Map.of(
                                            "type", "string",
                                            "description", "Keywords to search for, e.g. 'retry timeout'")),
                            "required", List.of("query")),
                    java.util.Set.of("code", "search"),
                    ToolDefinition.Risk.READ_ONLY);
        }

        @Override
        public ToolResult execute(ToolInput input) {
            Object raw = input.parameters().get("query");
            String query = raw == null ? null : raw.toString();
            if (query == null || query.isBlank()) {
                return ToolResult.failure("INVALID_INPUT", "parameter 'query' is required");
            }
            List<Snippet> hits = store.search(query, DEFAULT_LIMIT);
            return ToolResult.success(render(hits));
        }
    }
}
