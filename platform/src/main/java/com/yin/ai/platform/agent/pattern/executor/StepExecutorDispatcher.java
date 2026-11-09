package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.observe.StepExecutionGovernance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 步骤执行器调度器 — 根据 executor 标识路由到对应的 StepExecutor。
 * <p>
 * 路由规则：
 * - "llm" → LlmStepExecutor
 * - "web_search" → WebSearchStepExecutor（后续实现）
 * - "skill:xxx" → SkillStepExecutor（后续实现）
 * - "tool:xxx" → ToolStepExecutor（后续实现）
 * - 未匹配 → fallback 到 LlmStepExecutor
 */
public class StepExecutorDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StepExecutorDispatcher.class);

    private final List<StepExecutor> executors;
    private final StepExecutor fallback;
    private final StepExecutionGovernance governance;

    public StepExecutorDispatcher(List<StepExecutor> executors) {
        this(executors, null);
    }

    public StepExecutorDispatcher(List<StepExecutor> executors, StepExecutionGovernance governance) {
        // 【BUG 修复】priority 语义 = 数值越大越优先；原实现升序导致 service adapter 版
        // ViewArtifactStepExecutor(priority=50) 排在 pattern 版(priority=100) 前面，
        // view_artifact 步骤总是命中 service 版（依赖 minio artifactKey，PlanExecute 场景
        // 上下文只有 step.{dep}.output 变量 → no ref found 失败）。改降序后 pattern 版优先。
        this.executors = executors.stream()
                .sorted(Comparator.comparingInt(StepExecutor::priority).reversed())
                .toList();
        this.fallback = this.executors.stream()
                .filter(e -> "llm".equals(e.executorPrefix()))
                .findFirst()
                .orElse(this.executors.isEmpty() ? null : this.executors.get(0));
        this.governance = governance;
    }

    /**
     * 调度执行。
     *
     * @param executorId 步骤的 executor 标识
     * @param inputs     ContextManager 解析后的输入
     * @param ctx        执行上下文
     * @return 执行结果
     */
    public StepExecutorResult dispatch(String executorId, Map<String, String> inputs,
                                        AgentExecutionContext ctx) {
        StepExecutor executor = resolve(executorId);
        log.debug("[DISPATCH] step={} executor='{}' → {}",
                ctx.getCurrentStepId(), executorId, executor.getClass().getSimpleName());
        if (executor == null) {
            return StepExecutorResult.fail("NO_STEP_EXECUTOR", 0);
        }
        StepExecutionGovernance.ExecutionScope scope = governance != null
                ? governance.before(executorId, ctx) : null;
        if (scope != null && scope.rejectionReason() != null) {
            return StepExecutorResult.fail(scope.rejectionReason(), 0);
        }
        StepExecutorResult result;
        try {
            result = executor.execute(executorId, inputs, ctx);
        } catch (RuntimeException e) {
            result = StepExecutorResult.fail(e.getMessage(), 0);
        }
        if (governance != null) governance.after(executorId, ctx, scope, result);
        return result;
    }

    /**
     * 检查 executor 是否可用（用于 PlanValidator）。
     */
    public boolean isAvailable(String executorId) {
        if (executorId == null || executorId.equals("llm")) return true;
        return executors.stream().anyMatch(e -> executorId.startsWith(e.executorPrefix()));
    }

    private StepExecutor resolve(String executorId) {
        if (executorId == null || executorId.isBlank()) return fallback;

        for (StepExecutor exec : executors) {
            if (executorId.equals(exec.executorPrefix())
                    || executorId.startsWith(exec.executorPrefix() + ":")) {
                return exec;
            }
        }

        log.warn("[DISPATCH] no executor for '{}', falling back to LLM", executorId);
        return fallback;
    }
}
