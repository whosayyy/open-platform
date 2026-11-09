package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.gateway.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 步骤执行器 — 纯 LLM 生成（executor = "llm"）。
 * <p>
 * 将 ContextManager 解析后的输入组装为 Prompt，调用 LLM 生成输出。
 * 这是最通用的执行器，当步骤不需要外部工具时使用。
 */
public class LlmStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmStepExecutor.class);

    private final GatewayTextClient chatClient;

    public LlmStepExecutor(GatewayTextClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String executorPrefix() {
        return "llm";
    }

    @Override
    public StepExecutorResult execute(String executorId, Map<String, String> inputs,
                                       AgentExecutionContext ctx) {
        long start = System.currentTimeMillis();
        try {
            String prompt = buildPrompt(inputs, ctx);
            String response = callWithConfig(ctx, prompt);

            long duration = System.currentTimeMillis() - start;
            long tokens = estimateTokens(prompt, response);
            return StepExecutorResult.ok(response, tokens, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[LLM-EXEC] failed step={}: {}", ctx.getCurrentStepId(), e.getMessage());
            return StepExecutorResult.fail(e.getMessage(), duration);
        }
    }

    private String buildPrompt(Map<String, String> inputs, AgentExecutionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务目标：").append(ctx.getGoal()).append("\n\n");

        Object sessionContext = ctx.getVariable("sessionContext");
        if (sessionContext != null && !sessionContext.toString().isBlank()) {
            sb.append(sessionContext).append("\n\n");
        }

        if (!inputs.isEmpty()) {
            sb.append("=== 上下文输入 ===\n");
            for (Map.Entry<String, String> entry : inputs.entrySet()) {
                sb.append("[").append(entry.getKey()).append("]:\n");
                sb.append(entry.getValue()).append("\n\n");
            }
        }

        // 当前步骤描述
        if (ctx.getPlan() != null && ctx.getCurrentStepId() != null) {
            var step = ctx.getPlan().steps().stream()
                    .filter(s -> s.id().equals(ctx.getCurrentStepId()))
                    .findFirst();
            step.ifPresent(s -> sb.append("=== 当前步骤 ===\n")
                    .append(s.description()).append("\n"));
        }

        sb.append("\n=== INTERNAL WORKER CONTRACT ===\n")
                .append("This is an internal Plan-and-Execute worker call. Do not address the user, ask for confirmation, or say that more information is needed. ")
                .append("Produce the complete work product required by the current step for the next step to consume. ")
                .append("Keep meta commentary short. If the current step produces the final deliverable, return clean Markdown only.\n");

        if (isFinalArtifactStep(ctx)) {
            sb.append("\n=== FINAL ARTIFACT CONTRACT ===\n")
                    .append(contextValue(ctx, "finalArtifactContract",
                            "Return a standalone, complete Markdown deliverable. Do not mention prompts, agents, internal steps, context, drafts, or the workflow."))
                    .append('\n');
        } else {
            String workerContract = contextValue(ctx, "workerContract", "");
            if (!workerContract.isBlank()) sb.append(workerContract).append('\n');
        }

        return sb.toString();
    }

    private boolean isFinalArtifactStep(AgentExecutionContext ctx) {
        if (ctx.getPlan() == null || ctx.getCurrentStepId() == null) return false;
        String finalStepId = contextValue(ctx, "finalStepId", "final-artifact");
        return ctx.getPlan().steps().stream()
                .filter(step -> step.id().equals(ctx.getCurrentStepId()))
                .anyMatch(step -> "llm".equalsIgnoreCase(step.executor())
                        && finalStepId.equals(step.id()));
    }

    private String contextValue(AgentExecutionContext ctx, String key, String fallback) {
        Object value = ctx.getVariable(key);
        return value != null && !value.toString().isBlank() ? value.toString() : fallback;
    }

    private String callWithConfig(AgentExecutionContext ctx, String userPrompt) {
        String sp = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
        Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
        ModelExecutionOptions executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof ModelExecutionOptions value ? value : null;
        var response = chatClient.generate("agent:plan-step", userPrompt, sp, temp, executionOptions);
        if (response != null && response.getTokenUsage() != null) {
            ctx.accumulateTokenUsage(response.getTokenUsage());
        }
        String content = response != null ? response.getContent() : null;
        return content != null ? content : "";
    }

    private long estimateTokens(String input, String output) {
        // 粗略估算：中文约 2 字符/token
        return (input.length() + (output != null ? output.length() : 0)) / 2;
    }

    @Override
    public int priority() {
        return 0;  // 最高优先级（默认执行器）
    }
}
