package com.yin.ai.platform.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * 验证 {@link RuleModeClassifier} 的启发式规则路由：
 * 创作+调研关键词同时命中时必须返回 empty（交由 LLM 兜底），避免"写一份市场分析报告"被误路由到 PLAN_EXECUTE。
 */
@DisplayName("RuleModeClassifier")
class RuleModeClassifierTest {

    private final RuleModeClassifier classifier = new RuleModeClassifier();
    private final RouteContext ctx = RouteContext.of("u1", "s1");

    @Test
    @DisplayName("空/null 输入 → SIMPLE")
    void blankOrNullInputsToSimple() {
        assertThat(classifier.classify("", ctx)).hasValue(AgentMode.DIRECT);
        assertThat(classifier.classify(null, ctx)).hasValue(AgentMode.DIRECT);
    }

    @Test
    @DisplayName("极短输入（无关键词）→ SIMPLE")
    void shortInputToSimple() {
        assertThat(classifier.classify("你好", ctx)).hasValue(AgentMode.DIRECT);
    }

    @Test
    @DisplayName("创作+调研关键词同时命中 → empty（交给 LLM 兜底）")
    void planAndResearchTogetherDefer() {
        assertThat(classifier.classify("写一份市场分析报告", ctx)).isEmpty();
        assertThat(classifier.classify("请先分析市场，然后撰写报告", ctx)).isEmpty();
    }

    @Test
    @DisplayName("纯调研关键词 → DEEP_RESEARCH")
    void researchKeywordRoutesToDeepResearch() {
        assertThat(classifier.classify("调研AI芯片市场", ctx)).hasValue(AgentMode.DEEP_RESEARCH);
    }

    @Test
    @DisplayName("纯创作关键词 → PLAN_EXECUTE")
    void planKeywordRoutesToPlanExecute() {
        assertThat(classifier.classify("写一篇产品方案", ctx)).hasValue(AgentMode.PLAN_EXECUTE);
    }

    @Test
    @DisplayName("工具类关键词 → REACT")
    void toolKeywordRoutesToReact() {
        assertThat(classifier.classify("帮我搜索一下今天北京上海的天气情况", ctx))
                .hasValue(AgentMode.REACT);
    }
}
