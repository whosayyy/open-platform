package com.yin.ai.platform.core.router.query;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Query 标准化器 — 将用户原始输入标准化为缓存友好的统一格式。
 * <p>
 * 标准化步骤：
 * <ol>
 *   <li>trim 首尾空白</li>
 *   <li>小写化 (toLowerCase)</li>
 *   <li>连续空白压缩为单个空格</li>
 *   <li>中文全角字符转半角（ASCII 范围内）</li>
 *   <li>应用调用方提供的确定性同义词映射</li>
 * </ol>
 * <p>
 * 同义词归一化只做确定性映射，不做语义消歧。
 * 语义消歧是 {@code SemanticMatcher} (V2.1) 的职责。
 */
public class QueryNormalizer {

    /** 连续空白（空格/制表符/全角空格）压缩为一个空格 */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s\\u3000]+");

    private final Map<String, String> synonyms;

    public QueryNormalizer() {
        this(Map.of());
    }

    /** Adds application-owned deterministic mappings without teaching the platform a business vocabulary. */
    public QueryNormalizer(Map<String, String> applicationSynonyms) {
        this.synonyms = applicationSynonyms == null ? Map.of() : Map.copyOf(applicationSynonyms);
    }

    /**
     * 对原始 Query 进行标准化。
     *
     * @param rawQuery 用户原始输入，可能为 null 或空
     * @return 标准化后的查询字符串，null/空输入返回空字符串
     */
    public String normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }

        // 1. trim
        String q = rawQuery.trim();

        // 2. 全角转半角（ASCII 范围内）
        q = fullWidthToHalfWidth(q);

        // 3. 小写化
        q = q.toLowerCase();

        // 4. 连续空白压缩
        q = WHITESPACE_PATTERN.matcher(q).replaceAll(" ");

        // 5. 同义词归一化
        q = applySynonyms(q);

        // 6. 最终 trim（处理全角空格在步骤2转半角后产生的首尾空白）
        q = q.strip();

        return q;
    }

    /**
     * 全角 ASCII 字符转半角。
     * <p>
     * 全角字符范围：FF01–FF5E 对应半角 21–7E，偏移 0xFEE0。
     * 全角空格 3000 → 半角空格 0020。
     */
    private String fullWidthToHalfWidth(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '　') {
                sb.append(' ');
            } else if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 应用同义词归一化 — 按最长匹配优先替换。
     */
    private String applySynonyms(String query) {
        // 按 key 长度降序排序，确保长短语优先替换
        String[] keys = synonyms.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toArray(String[]::new);

        for (String key : keys) {
            if (query.contains(key)) {
                query = query.replace(key, synonyms.get(key));
            }
        }
        return query;
    }
}
