package com.yin.ai.platform.core.rag.grounding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic claim-level citation coverage analyzer.
 *
 * <p>Answers are evaluated by paragraph/list-item rather than by document-level citation count.
 * A citation collected in a references section therefore cannot make unrelated factual paragraphs
 * appear grounded. Supported citation forms are evidence ids, numbered footnotes and verified URLs.</p>
 */
public final class ClaimCitationAnalyzer {

    private static final Pattern EVIDENCE_CITATION = Pattern.compile("\\[E\\d+]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERED_CITATION = Pattern.compile("\\[(?:\\d{1,3}(?:\\s*[-,]\\s*\\d{1,3})*)]");
    private static final Pattern URL_CITATION = Pattern.compile("(?i)(?:https?://|\\[[^]\\r\\n]+]\\(https?://)");
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+|>\\s*)");
    private static final Pattern HEADING_OR_RULE = Pattern.compile("^\\s*(?:#{1,6}\\s+|[-*_]{3,}\\s*$)");
    private static final Pattern REFERENCE_HEADING = Pattern.compile(
            "(?i)^\\s*#{0,6}\\s*(?:references?|sources?|citations?|参考资料|引用资料|信息来源|来源)\\s*[:：]?\\s*$");
    private static final Pattern FACT_SIGNAL = Pattern.compile(
            "(?i)(?:\\d|年|月|日|%|亿元|亿美元|发布|宣布|表示|显示|指出|增长|下降|达到|超过|发生|成立|位于|" +
                    "reported|announced|according|increased|decreased|founded|located|percent)");

    private ClaimCitationAnalyzer() {
    }

    public static Analysis analyze(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new Analysis(0, 0, 1.0, List.of());
        }
        List<String> units = claimUnits(stripCodeFences(markdown));
        List<String> unsupported = new ArrayList<>();
        int claims = 0;
        int supported = 0;
        boolean referencesSection = false;
        for (String unit : units) {
            String trimmed = unit.trim();
            if (REFERENCE_HEADING.matcher(trimmed).matches()) {
                referencesSection = true;
                continue;
            }
            if (referencesSection || HEADING_OR_RULE.matcher(trimmed).find()) {
                continue;
            }
            String plain = MARKDOWN_PREFIX.matcher(trimmed).replaceFirst("").trim();
            if (!looksLikeFactualClaim(plain)) {
                continue;
            }
            claims++;
            if (hasCitation(trimmed)) {
                supported++;
            } else {
                unsupported.add(abbreviate(plain, 180));
            }
        }
        double coverage = claims == 0 ? 1.0 : (double) supported / claims;
        return new Analysis(claims, supported, coverage, unsupported);
    }

    private static List<String> claimUnits(String markdown) {
        List<String> units = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : markdown.split("\\R", -1)) {
            String trimmed = line.trim();
            boolean listItem = trimmed.matches("^(?:[-*+]\\s+|\\d+[.)]\\s+).+");
            if (trimmed.isBlank() || listItem || HEADING_OR_RULE.matcher(trimmed).find()) {
                flush(paragraph, units);
                if (!trimmed.isBlank()) {
                    units.add(trimmed);
                }
            } else {
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(trimmed);
            }
        }
        flush(paragraph, units);
        return units;
    }

    private static void flush(StringBuilder paragraph, List<String> units) {
        if (paragraph.length() > 0) {
            units.add(paragraph.toString());
            paragraph.setLength(0);
        }
    }

    private static boolean looksLikeFactualClaim(String text) {
        String withoutCitations = EVIDENCE_CITATION.matcher(text).replaceAll("");
        withoutCitations = NUMBERED_CITATION.matcher(withoutCitations).replaceAll("").trim();
        if (withoutCitations.endsWith("?") || withoutCitations.endsWith("？")) return false;
        String lower = withoutCitations.toLowerCase(Locale.ROOT);
        if (lower.startsWith("下一步") || lower.startsWith("建议") || lower.startsWith("可以")
                || lower.startsWith("请") || lower.startsWith("总结")) return false;
        int length = withoutCitations.codePointCount(0, withoutCitations.length());
        return (length >= 18 || (length >= 6 && FACT_SIGNAL.matcher(withoutCitations).find()))
                && withoutCitations.matches("(?s).*\\p{L}.*");
    }

    private static boolean hasCitation(String text) {
        return EVIDENCE_CITATION.matcher(text).find()
                || NUMBERED_CITATION.matcher(text).find()
                || URL_CITATION.matcher(text).find();
    }

    private static String stripCodeFences(String value) {
        return value.replaceAll("(?s)```.*?```", " ");
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public record Analysis(int claimCount, int supportedClaimCount,
                           double coverage, List<String> unsupportedClaims) {
        public Analysis {
            unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
        }

        public boolean meets(double threshold) {
            return coverage + 1.0e-9 >= Math.max(0.0, Math.min(1.0, threshold));
        }
    }
}
