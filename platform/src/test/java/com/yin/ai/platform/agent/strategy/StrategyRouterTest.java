package com.yin.ai.platform.agent.strategy;

import com.yin.ai.platform.agent.AgentContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【企业级缺口修复回归测试】DIRECT_SKILL 快捷路径的防回归语义。
 * <p>
 * 修复点：默认 SIMPLE→REACT_LOOP（原默认 DIRECT_SKILL 会让简单任务全部改走快捷路径）；
 * 且 isActive() 防止"未配置 analyzer 时误走快捷路径"。
 */
class StrategyRouterTest {

    private static final class SimpleAnalyzer implements ComplexityAnalyzer {
        @Override
        public ComplexityAssessment assess(String userInput, AgentContext ctx) {
            return ComplexityAssessment.simple("short question");
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public String name() {
            return "test-simple";
        }
    }

    @Test
    void defaultConfigRoutesSimpleToReactLoop() {
        // 默认映射：SIMPLE → REACT_LOOP（防回归，原为 DIRECT_SKILL）
        ComplexityConfig config = ComplexityConfig.defaults();
        StrategyRouter router = new StrategyRouter(List.of(new SimpleAnalyzer()), config);

        ExecutionStrategy strategy = router.route("今天天气怎么样", mock(AgentContext.class));

        assertThat(strategy).isEqualTo(ExecutionStrategy.REACT_LOOP);
    }

    @Test
    void explicitOptInRoutesSimpleToDirectSkill() {
        // 显式 opt-in：strategyMapping.SIMPLE=DIRECT_SKILL 时启用快捷路径
        ComplexityConfig config = new ComplexityConfig(
                true, 0.7, 0.5, false,
                Map.of(ComplexityLevel.SIMPLE, ExecutionStrategy.DIRECT_SKILL,
                        ComplexityLevel.MODERATE, ExecutionStrategy.REACT_LOOP,
                        ComplexityLevel.COMPLEX, ExecutionStrategy.REACT_LOOP));
        StrategyRouter router = new StrategyRouter(List.of(new SimpleAnalyzer()), config);

        ExecutionStrategy strategy = router.route("今天天气怎么样", mock(AgentContext.class));

        assertThat(strategy).isEqualTo(ExecutionStrategy.DIRECT_SKILL);
    }

    @Test
    void isActiveFalseWhenDisabled() {
        ComplexityConfig disabled = new ComplexityConfig(
                false, 0.7, 0.5, false,
                Map.of(ComplexityLevel.SIMPLE, ExecutionStrategy.DIRECT_SKILL,
                        ComplexityLevel.MODERATE, ExecutionStrategy.REACT_LOOP,
                        ComplexityLevel.COMPLEX, ExecutionStrategy.REACT_LOOP));
        StrategyRouter router = new StrategyRouter(List.of(new SimpleAnalyzer()), disabled);

        assertThat(router.isActive()).isFalse();
    }

    @Test
    void isActiveFalseWhenNoAnalyzers() {
        ComplexityConfig config = ComplexityConfig.defaults();
        StrategyRouter router = new StrategyRouter(List.of(), config);

        assertThat(router.isActive()).isFalse();
    }

    @Test
    void isActiveTrueWhenEnabledWithAnalyzer() {
        ComplexityConfig config = ComplexityConfig.defaults();
        StrategyRouter router = new StrategyRouter(List.of(new SimpleAnalyzer()), config);

        assertThat(router.isActive()).isTrue();
    }
}
