package com.yin.ai.platform.rag.trigger;

import com.yin.ai.platform.agent.AgentContext;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于规则的默认知识边界检测器。
 * <p>
 * 规则：
 * <ol>
 *   <li>包含时间限定词（"今天/最近/本周/昨天"）+ 事件查询 → 需要 RAG</li>
 *   <li>包含专有名词特征（大写英文/引号括起）→ 需要 RAG</li>
 *   <li>纯常识/通用问题 → 不需要 RAG</li>
 * </ol>
 */
public class RuleBasedBoundaryDetector implements KnowledgeBoundaryDetector {

    private static final Set<String> TEMPORAL_KEYWORDS = Set.of(
            "今天", "昨天", "本周", "上周", "最近", "当前", "现在",
            "最新", "刚刚", "今日", "近日", "近期", "今年");

    private static final Set<String> GENERAL_KNOWLEDGE_KEYWORDS = Set.of(
            "什么", "怎么", "如何", "为什么", "定义", "概念",
            "原理", "方法", "步骤");

    private static final Pattern PROPER_NOUN_PATTERN = Pattern.compile(
            "[A-Z][a-z]+\\s[A-Z][a-z]+|「.+」|\".+\"");

    @Override
    public KnowledgeBoundaryAssessment detect(String userInput, AgentContext ctx) {
        if (userInput == null || userInput.isBlank()) {
            return KnowledgeBoundaryAssessment.noRagNeeded("empty input");
        }

        String input = userInput.trim();

        // Rule 1: Temporal + event → needs RAG
        boolean hasTemporal = TEMPORAL_KEYWORDS.stream().anyMatch(input::contains);
        if (hasTemporal) {
            return KnowledgeBoundaryAssessment.needsRag("news", "temporal query: " + findFirst(input, TEMPORAL_KEYWORDS));
        }

        // Rule 2: Proper nouns (companies, people, products) → likely needs RAG
        if (PROPER_NOUN_PATTERN.matcher(input).find()) {
            return KnowledgeBoundaryAssessment.needsRag("knowledge", "proper noun detected");
        }

        // Rule 3: Pure general knowledge → no RAG needed
        boolean isGeneral = GENERAL_KNOWLEDGE_KEYWORDS.stream().anyMatch(input::contains);
        boolean isShort = input.length() < 20;
        if (isGeneral && isShort) {
            return KnowledgeBoundaryAssessment.noRagNeeded("general knowledge question");
        }

        // Uncertain → conservative (trigger RAG)
        return KnowledgeBoundaryAssessment.uncertain("uncertain, conservative trigger");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private static String findFirst(String input, Set<String> keywords) {
        return keywords.stream().filter(input::contains).findFirst().orElse("unknown");
    }
}
