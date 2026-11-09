package com.yin.ai.platform.agent.pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 规则分类器 — 基于启发式规则快速分类，零 LLM 开销。
 * <p>
 * 复用现有 DefaultComplexityAnalyzer 的规则思路，扩展为四模式分类。
 * 规则优先级：
 * 1. 极短输入（< 15 字符）且无特殊关键词 → SIMPLE
 * 2. 同时包含创作类与调研类关键词 → empty（规则无法判定，交给 LLM 兜底）
 * 3. 包含调研类关键词 → DEEP_RESEARCH
 * 4. 包含创作类关键词 → PLAN_EXECUTE
 * 5. 多步描述 → PLAN_EXECUTE
 * 6. 包含工具类关键词 → REACT
 * 7. 无法判断 → empty（交给下一个分类器）
 */
public class RuleModeClassifier implements ModeClassifier {

    private static final Logger log = LoggerFactory.getLogger(RuleModeClassifier.class);

    private static final Pattern PLAN_PATTERN = Pattern.compile(
            "写|撰写|生成|报告|文章|方案|设计|规划|大纲|论文|总结|汇报|PPT|演示",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RESEARCH_PATTERN = Pattern.compile(
            "调研|分析|对比|趋势|市场|行业|研究|综述|现状|前景|竞品|评估|深度",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TOOL_PATTERN = Pattern.compile(
            "搜索|查询|天气|翻译|计算|转换|发送|创建|删除|修改|更新|部署",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTI_STEP_PATTERN = Pattern.compile(
            "首先.*然后|第一.*第二|步骤|1\\..*2\\.|①.*②",
            Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<AgentMode> classify(String userInput, RouteContext ctx) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.of(AgentMode.DIRECT);
        }

        String input = userInput.trim();

        // 规则 1：极短输入 → SIMPLE
        if (input.length() < 15 && !PLAN_PATTERN.matcher(input).find()
                && !RESEARCH_PATTERN.matcher(input).find()) {
            log.debug("[ROUTER] rule=short-input → SIMPLE");
            return Optional.of(AgentMode.DIRECT);
        }

        // 规则 2/3：创作类 → PLAN_EXECUTE；调研类 → DEEP_RESEARCH
        boolean planKeyword = PLAN_PATTERN.matcher(input).find();
        boolean researchKeyword = RESEARCH_PATTERN.matcher(input).find();
        if (planKeyword && researchKeyword) {
            // 同时命中创作与调研关键词（如"写一份市场分析报告"），规则无法判定，
            // 交由后续分类器（LLM 兜底）决策，避免误路由到 PLAN_EXECUTE
            log.debug("[ROUTER] rule=plan+research ambiguous → defer");
            return Optional.empty();
        }
        if (researchKeyword) {
            log.debug("[ROUTER] rule=research-keyword → DEEP_RESEARCH");
            return Optional.of(AgentMode.DEEP_RESEARCH);
        }
        if (planKeyword) {
            log.debug("[ROUTER] rule=plan-keyword → PLAN_EXECUTE");
            return Optional.of(AgentMode.PLAN_EXECUTE);
        }

        // 规则 4：多步描述 → PLAN_EXECUTE
        if (MULTI_STEP_PATTERN.matcher(input).find()) {
            log.debug("[ROUTER] rule=multi-step → PLAN_EXECUTE");
            return Optional.of(AgentMode.PLAN_EXECUTE);
        }

        // 规则 5：工具类 → REACT
        if (TOOL_PATTERN.matcher(input).find()) {
            log.debug("[ROUTER] rule=tool-keyword → REACT");
            return Optional.of(AgentMode.REACT);
        }

        // 无法判断
        return Optional.empty();
    }

    @Override
    public int priority() {
        return 10;  // 高优先级，规则先行
    }
}
