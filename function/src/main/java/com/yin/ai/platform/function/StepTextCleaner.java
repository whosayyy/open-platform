package com.yin.ai.platform.function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 步骤展示文本净化器 — 用于 step description / name / summary / detail 字段。
 * <p>
 * <b>职责</b>：消除 LLM 上报的"内嵌 JSON 字符串"污染 UI 的问题。
 * 数据流：
 * <pre>
 * LLM 输出嵌入 JSON 字符串（如 {"goal":"用户...","articleType":"分析"}）
 *   → StepTextCleaner.clean(text, 60)
 *   → "用户：帮我写一篇关于 DeepSeek 的文章…"（可读摘录）
 * </pre>
 * <p>
 * 字段提取优先级（与前端 utils/humanize.js 对齐）：
 * goal → response → answer → content → result → text → summary →
 * title → name → message → thinking → reasoning → description →
 * articleType → structure → data → step
 * <p>
 * 对齐改造：详见 docs/agent-frontend-rework.md §7.4。前端的 humanize 只能做兜底净化，
 * 权威净化应在此类完成，避免前端再解析。
 *
 * @author WorkBuddy
 */
public final class StepTextCleaner {

    /** 字段提取优先级（与前端 utils/humanize.js 的 READABLE_KEYS 对齐 + 增加业务字段） */
    private static final String[] READABLE_KEYS = {
            "goal", "response", "answer", "content", "result",
            "text", "summary", "title", "name", "message",
            "thinking", "reasoning", "description",
            "articleType", "structure", "data", "step",
            "user_intent", "task"
    };

    private static final Set<String> SKIP_KEYS = Set.of(
            "executor", "dependsOn", "nodeId", "id", "schemaVersion", "version"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepTextCleaner() {}

    /**
     * 净化并裁剪文本。
     *
     * <p>规则：
     * <ol>
     *   <li>null/空白 → 返回空串</li>
     *   <li>以 {@code {} } 或 {@code [] } 开头的字符串 → 尝试解析为 JSON，提取可读字段</li>
     *   <li>普通文本 → 原样返回，超长截断加省略号</li>
     * </ol>
     *
     * @param raw    原始文本
     * @param maxLen 最大长度（含省略号）；0 表示不截断
     * @return 净化后的可读文本
     */
    public static String clean(String raw, int maxLen) {
        String extracted = extract(raw);
        if (extracted == null || extracted.isEmpty()) {
            return "";
        }
        if (maxLen <= 0 || extracted.length() <= maxLen) {
            return extracted;
        }
        // 中文按字符算，英文按词更合理。这里按 char 截断（简单粗暴可接受）
        return extracted.substring(0, maxLen) + "…";
    }

    /**
     * 提取可读文本，不截断。
     *
     * @param raw 原始文本
     * @return 提取结果；无任何可读字段返回空串
     */
    public static String extract(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";

        // 优先按 JSON 解析
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonNode node = MAPPER.readTree(t);
                String readable = fromJsonNode(node);
                if (readable != null && !readable.isBlank()) {
                    return readable.trim();
                }
            } catch (Exception ignored) {
                // 不是合法 JSON，原样返回
            }
        }

        // [fix] 嵌入 JSON 子串净化：检测文本内 {...} JSON 块并净化（避免 LLM 输出
        // "1. 简报\n{\"goal\":\"...\"}" 混排泄漏 schema 到 UI）
        if (t.contains("{") && t.contains("}")) {
            String cleaned = humanizeEmbeddedJson(t);
            if (cleaned != null && !cleaned.isEmpty() && !cleaned.equals(t)) {
                return cleaned;
            }
        }

        return t;
    }

    /**
     * 检测文本内嵌入的 JSON 子串并净化；非 JSON 部分保留原文。
     * 用花括号平衡剥离多轮嵌套（如 {"context":{"goal":"..."}}）。
     */
    static String humanizeEmbeddedJson(String raw) {
        String current = raw;
        boolean[] changed = { false };
        int safety = 5;
        while (safety-- > 0) {
            String prev = current;
            current = stripEmbeddedJsonOnce(current, changed);
            if (current.equals(prev)) break;
        }
        return changed[0] ? current : raw;
    }

    /** 单轮剥离 {...} JSON 子串；通过 changed 数组传出是否发生过替换（lambda 捕获 final 友好） */
    private static String stripEmbeddedJsonOnce(String input, boolean[] changed) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{[^{}]*\\}");
        java.util.regex.Matcher m = p.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String segment = m.group();
            String replacement = segment;
            try {
                JsonNode node = MAPPER.readTree(segment);
                String readable = fromJsonNode(node);
                if (readable != null && !readable.isBlank() && !readable.equals(segment.trim())) {
                    replacement = readable;
                    changed[0] = true;
                }
            } catch (Exception ignored) {
                // 不是合法 JSON，保持原文
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从 JsonNode 中按字段优先级提取可读文本。
     * <p>
     * 优先命中：标量字段（goal 等）；命中对象时递归；数组取首条；
     * 全部未命中 → 拼接所有标量值（跳过结构化字段）。
     */
    private static String fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return null;

        if (node.isTextual()) {
            String v = node.asText().trim();
            return v.isEmpty() ? null : v;
        }

        if (node.isValueNode()) {
            return node.asText().trim();
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                String r = fromJsonNode(child);
                if (r != null && !r.isBlank()) return r;
            }
            return null;
        }

        if (node.isObject()) {
            // 1. 按优先级遍历
            LinkedHashSet<String> tried = new LinkedHashSet<>();
            for (String key : READABLE_KEYS) {
                JsonNode v = node.get(key);
                if (v == null || v.isNull()) {
                    tried.add(key);
                    continue;
                }
                String r = fromJsonNode(v);
                if (r != null && !r.isBlank()) return r;
            }

            // 2. 兜底：拼接所有非结构化字段
            StringBuilder sb = new StringBuilder();
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String k = fieldNames.next();
                if (SKIP_KEYS.contains(k) || isTried(k, tried)) continue;
                JsonNode v = node.get(k);
                if (v == null || v.isNull()) continue;
                String r = fromJsonNode(v);
                if (r != null && !r.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(r);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        return node.asText().trim();
    }

    private static boolean isTried(String key, LinkedHashSet<String> tried) {
        return tried.contains(key);
    }
}
