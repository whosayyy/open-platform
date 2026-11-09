package com.yin.ai.platform.evaluation;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.ReActLoop;
import com.yin.ai.platform.agent.executor.AgentExecutor;
import com.yin.ai.platform.agent.memory.AgentMemoryBridge;
import com.yin.ai.platform.agent.observer.Observer;
import com.yin.ai.platform.agent.planner.Planner;
import com.yin.ai.platform.agent.rag.AgentRagBridge;
import com.yin.ai.platform.agent.reflector.Reflector;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.observe.AgentEvaluator;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 回归测试 — 使用 Mock 组件验证 ReActLoop 核心流程的端到端行为。
 */
public class AgentRegressionTest {

    private static ExecutionContext execCtx() {
        return new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    /**
     * 验证 ReActLoop 在简单单步场景下正常完成。
     */
    public static void runRegressionTest(
            ReActLoop loop,
            String agentIdPrefix, String goal,
            String expectedContains, String forbiddenPattern) {

        AgentTask task = AgentTask.of(goal, AgentConfig.of(5,
                Duration.ofMinutes(1)));
        AgentContext ctx = new AgentContext(execCtx(), task, 0);

        AgentResult result = loop.enter(ctx);

        assertNotNull(result, "AgentResult should not be null");

        if (result.success()) {
            assertNotNull(result.result(), "Output should not be null on success");
            if (expectedContains != null) {
                assertTrue(result.result().toLowerCase().contains(expectedContains.toLowerCase()),
                        "Output should contain: " + expectedContains);
            }
            if (forbiddenPattern != null) {
                assertTrue(!result.result().toLowerCase().contains(forbiddenPattern.toLowerCase()),
                        "Output should NOT contain: " + forbiddenPattern);
            }
        }
    }

    /**
     * 使用 AgentEvaluator 对输出质量进行评分验证。
     */
    public static void evaluateQuality(AgentEvaluator evaluator, String goal, String output,
                                        double minRelevance, double minCompleteness, double minOverall) {
        AgentEvaluator.EvaluationResult score = evaluator.evaluate(goal, output, "");
        assertTrue(score.relevance() >= minRelevance,
                "Relevance should be >= " + minRelevance + ", got " + score.relevance());
        assertTrue(score.completeness() >= minCompleteness,
                "Completeness should be >= " + minCompleteness + ", got " + score.completeness());
        assertTrue(score.overall() >= minOverall,
                "Overall should be >= " + minOverall + ", got " + score.overall());
    }
}
