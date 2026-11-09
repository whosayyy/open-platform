package com.yin.ai.platform.agent.pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Safe deterministic fallback when the user-facing summary model is unavailable. */
public class DefaultStepSummaryGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public StepSummary generate(TaskStep step, StepResult result, String rawOutput, String nextAction) {
        String readable = toReadableMarkdown(rawOutput);
        String content = readable.isBlank()
                ? (result.success() ? "本步骤已完成。" : "本步骤执行失败。")
                : limit(readable, 1200);

        List<String> findings = extractFindings(content);
        if (findings.isEmpty()) findings = List.of(firstSentence(content, 240));
        return new StepSummary(step.id(), limit(step.description(), 96), content,
                findings, nextAction == null || nextAction.isBlank() ? "继续执行后续计划" : nextAction);
    }

    /** Convert structured worker output to readable Markdown without returning raw JSON. */
    public String toReadableMarkdown(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return "";
        String trimmed = rawOutput.trim();
        if (!looksLikeJson(trimmed)) return stripCodeFence(trimmed);
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            String markdown = searchResultsMarkdown(root);
            if (!markdown.isBlank()) return markdown;
            String readable = extractReadable(root, 0);
            return readable == null ? "" : stripCodeFence(readable.trim());
        } catch (Exception ignored) {
            // A JSON-looking value must not be reflected back to the UI when it
            // cannot be parsed safely.
            return "";
        }
    }

    public boolean looksLikeJson(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        return trimmed.startsWith("{")
                || trimmed.startsWith("[")
                || trimmed.startsWith("```json");
    }

    private String searchResultsMarkdown(JsonNode root) {
        JsonNode results = root.isObject() ? root.path("results") : root;
        if (!results.isArray() || results.isEmpty()) return "";
        StringBuilder markdown = new StringBuilder("已完成资料检索与核准，获得以下可用信息：\n\n");
        int count = 0;
        for (JsonNode item : results) {
            if (!item.isObject()) continue;
            String title = text(item, "title", "name");
            String snippet = text(item, "content", "snippet", "summary", "text");
            String url = text(item, "url", "link");
            String source = text(item, "source");
            if (title.isBlank() && snippet.isBlank()) continue;
            markdown.append("- **").append(escapeMarkdown(title.isBlank() ? "检索结果" : limit(title, 100))).append("**");
            if (!source.isBlank()) markdown.append("（").append(escapeMarkdown(limit(source, 40))).append("）");
            if (!snippet.isBlank()) markdown.append("：").append(limit(snippet.replaceAll("\\s+", " "), 220));
            if (!url.isBlank()) markdown.append(" [查看来源](").append(url.replace(" ", "%20")).append(")");
            markdown.append('\n');
            if (++count >= 5) break;
        }
        return count == 0 ? "" : markdown.toString().trim();
    }

    private String extractReadable(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > 4) return "";
        if (node.isTextual()) return node.asText();
        if (node.isValueNode()) return node.asText();
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode child : node) {
                String value = extractReadable(child, depth + 1);
                if (!value.isBlank()) values.add("- " + limit(value.replaceAll("\\s+", " "), 240));
                if (values.size() >= 5) break;
            }
            return String.join("\n", values);
        }
        for (String key : List.of("response", "answer", "content", "result", "text", "summary", "description")) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String readable = extractReadable(value, depth + 1);
                if (!readable.isBlank()) return readable;
            }
        }
        return "";
    }

    private List<String> extractFindings(String markdown) {
        List<String> findings = new ArrayList<>();
        for (String line : markdown.split("\\R")) {
            String item = line.trim().replaceFirst("^[-*+]\\s+", "");
            if (!item.equals(line.trim()) && !item.isBlank()) findings.add(limit(item, 240));
            if (findings.size() == 3) break;
        }
        return findings;
    }

    private String firstSentence(String value, int max) {
        if (value == null || value.isBlank()) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        int end = compact.length();
        for (char marker : new char[]{'。', '！', '？', '.', '!', '?'}) {
            int index = compact.indexOf(marker);
            if (index >= 0) end = Math.min(end, index + 1);
        }
        return limit(compact.substring(0, end), max);
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText().trim();
        }
        return "";
    }

    private String stripCodeFence(String value) {
        return value.replaceFirst("(?s)^```(?:markdown|md|text)?\\s*", "")
                .replaceFirst("\\s*```$", "").trim();
    }

    private String escapeMarkdown(String value) {
        return value.replace("[", "\\[").replace("]", "\\]");
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
