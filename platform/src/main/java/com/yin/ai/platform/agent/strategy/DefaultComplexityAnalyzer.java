package com.yin.ai.platform.agent.strategy;

import com.yin.ai.platform.agent.AgentContext;

import java.util.Set;

/**
 * 基于规则 + 启发式的默认复杂度分析器。
 * <p>
 * 规则优先级（从高到低）：
 * <ol>
 *   <li>包含"设计/方案/分析/规划/流程/步骤/架构"关键词 → COMPLEX</li>
 *   <li>包含"对比/比较/区别/哪个好/优缺点"关键词 → MODERATE</li>
 *   <li>需要多工具调用（tools > 3）→ MODERATE+</li>
 *   <li>包含多个问号或明显子问题 → MODERATE+</li>
 *   <li>问题长度 < 10 字符 → SIMPLE</li>
 *   <li>默认 → SIMPLE</li>
 * </ol>
 */
public class DefaultComplexityAnalyzer implements ComplexityAnalyzer {

    private static final Set<String> COMPLEX_KEYWORDS = Set.of(
            "设计", "方案", "分析", "规划", "流程", "步骤", "架构",
            "策略", "建议", "推荐方案", "优化", "排查");

    private static final Set<String> MODERATE_KEYWORDS = Set.of(
            "对比", "比较", "区别", "哪个好", "优缺点", "差异",
            "总结", "汇总", "整理", "归纳",
            "生成", "简报", "摘要", "制作");

    @Override
    public ComplexityAssessment assess(String userInput, AgentContext ctx) {
        if (userInput == null || userInput.isBlank()) {
            return ComplexityAssessment.simple("empty input");
        }

        String input = userInput.trim();
        int length = input.length();

        // Rule 1: Complex keywords
        for (String kw : COMPLEX_KEYWORDS) {
            if (input.contains(kw)) {
                return ComplexityAssessment.complex("keyword: " + kw);
            }
        }

        // Rule 2: Moderate keywords
        for (String kw : MODERATE_KEYWORDS) {
            if (input.contains(kw)) {
                return ComplexityAssessment.moderate("keyword: " + kw);
            }
        }

        // Rule 3: Multiple sub-questions (multiple '?' or numbered lists)
        long questionMarks = input.chars().filter(c -> c == '?' || c == '？').count();
        if (questionMarks >= 2) {
            return ComplexityAssessment.moderate("multiple questions: " + questionMarks);
        }

        // Rule 4: Line-separated multi-step instructions (e.g., "1. ...\n2. ...")
        long numberedLines = input.lines()
                .filter(line -> line.stripLeading().matches("^\\d+[.、)]\\s.*"))
                .count();
        if (numberedLines >= 2) {
            return ComplexityAssessment.moderate("numbered steps: " + numberedLines);
        }

        // Rule 5: Very short input
        if (length < 10) {
            return ComplexityAssessment.simple("short input: " + length + " chars");
        }

        return ComplexityAssessment.simple("default");
    }

    @Override
    public int priority() {
        return 100;
    }
}
