package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 【BUG 修复回归测试】StepExecutorDispatcher 的路由语义：
 * <p>
 * 原实现升序排序（priority 小者在前）+ service adapter 版 ViewArtifactStepExecutor(50)
 * 与 pattern 版 ViewArtifactStepExecutor(100) 同时存在时，view_artifact 总是命中 service 版，
 * 而 service 版在 PlanExecute 场景读不到 minio artifactKey（上下文只有 step.{dep}.output 变量）
 * → no ref found 失败。
 * <p>
 * 修复：priority 语义 = 数值越大越优先（降序），pattern 版（100）优先于 service 版（50）。
 */
class StepExecutorDispatcherTest {

    private static class MockExecutor implements StepExecutor {
        private final String prefix;
        private final int priority;
        private final StepExecutorResult result;

        MockExecutor(String prefix, int priority, StepExecutorResult result) {
            this.prefix = prefix;
            this.priority = priority;
            this.result = result;
        }

        @Override
        public String executorPrefix() {
            return prefix;
        }

        @Override
        public StepExecutorResult execute(String executorId, Map<String, String> inputs,
                                          AgentExecutionContext ctx) {
            return result;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    @Test
    void viewArtifactPrefersHigherPriorityExecutor() {
        // service 版（50）与 pattern 版（100）前缀相同 —— 应命中 100 的 pattern 版
        StepExecutorResult highResult = StepExecutorResult.ok("pattern-content", 0, 0);
        StepExecutorResult lowResult = StepExecutorResult.ok("service-content", 0, 0);
        MockExecutor pattern = new MockExecutor("view_artifact", 100, highResult);
        MockExecutor service = new MockExecutor("view_artifact", 50, lowResult);
        MockExecutor llm = new MockExecutor("llm", 0, StepExecutorResult.ok("llm", 0, 0));

        StepExecutorDispatcher dispatcher = new StepExecutorDispatcher(List.of(service, pattern, llm));

        StepExecutorResult result = dispatcher.dispatch("view_artifact", Map.of(),
                mock(AgentExecutionContext.class));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("pattern-content");
    }

    @Test
    void llmRoutesToLlmExecutorEvenWhenPriorityIsLowest() {
        MockExecutor pattern = new MockExecutor("view_artifact", 100, StepExecutorResult.ok("v", 0, 0));
        MockExecutor llm = new MockExecutor("llm", 0, StepExecutorResult.ok("llm-content", 0, 0));

        StepExecutorDispatcher dispatcher = new StepExecutorDispatcher(List.of(pattern, llm));

        StepExecutorResult result = dispatcher.dispatch("llm", Map.of(),
                mock(AgentExecutionContext.class));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("llm-content");
    }

    @Test
    void dispatcherHasLlmFallback() {
        MockExecutor pattern = new MockExecutor("view_artifact", 100, StepExecutorResult.ok("v", 0, 0));
        MockExecutor llm = new MockExecutor("llm", 0, StepExecutorResult.ok("fallback", 0, 0));

        StepExecutorDispatcher dispatcher = new StepExecutorDispatcher(List.of(pattern, llm));

        // 未注册的执行器 → fallback 到 llm
        StepExecutorResult result = dispatcher.dispatch("unknown_tool", Map.of(),
                mock(AgentExecutionContext.class));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("fallback");
    }
}
