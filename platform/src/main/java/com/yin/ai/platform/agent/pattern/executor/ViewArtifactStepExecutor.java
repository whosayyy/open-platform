package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 产物回读执行器 — 把依赖步骤的完整输出重新注入给后续 LLM 步骤。
 * <p>
 * 设计理由：PlanExecute 保存步骤产物后只把摘要/引用传给下游，
 * 最终编辑步骤需要全文（如完整文章草稿）时，通过本执行器把依赖步骤
 * 的 {@code step.{dep}.output} 上下文变量拼接回来，避免对对象存储的额外依赖。
 */
public class ViewArtifactStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ViewArtifactStepExecutor.class);

    @Override
    public String executorPrefix() {
        return "view_artifact";
    }

    @Override
    public StepExecutorResult execute(String executorId, Map<String, String> inputs,
                                      AgentExecutionContext ctx) {
        if (ctx.getPlan() == null) {
            return StepExecutorResult.fail("view_artifact: no plan in context", 0);
        }

        StringBuilder sb = new StringBuilder();
        String currentId = ctx.getCurrentStepId();
        for (var step : ctx.getPlan().steps()) {
            if (!step.id().equals(currentId) || step.dependsOn() == null) continue;
            for (String dep : step.dependsOn()) {
                Object raw = ctx.getVariable("step." + dep + ".output");
                if (raw == null || raw.toString().isBlank()) {
                    log.warn("[VIEW_ARTIFACT] dependency step '{}' has no output, skipping", dep);
                    continue;
                }
                sb.append("=== 产物：").append(dep).append(" ===\n")
                        .append(raw).append("\n\n");
            }
        }
        if (sb.length() > 0) {
            String content = sb.toString().trim();
            return StepExecutorResult.ok(content, content.length() / 2, 0);
        }

        // 回退：仅有对象存储引用时返回占位，否则失败让调度器可见
        String artifactKey = inputs.get("artifactKey");
        if (artifactKey != null && !artifactKey.isBlank()) {
            return StepExecutorResult.ok("[artifact:" + artifactKey + "]", 0, 0);
        }
        return StepExecutorResult.fail("view_artifact: no readable dependency output", 0);
    }

    @Override
    public int priority() {
        return 100;
    }
}
