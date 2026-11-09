package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.rag.enhanced.EnhancedRagService;
import com.yin.ai.platform.rag.enhanced.RagRetrievalSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Plan-and-Execute / DeepResearch 的企业知识库检索执行器（RAG）。
 *
 * <p>executor 标识：{@code rag_search}。统一复用 EnhancedRagService 五阶段管线
 * （重写→多路召回→RRF→LLM精排→去重）；RAG 在线质量指标已在
 * EnhancedRagService 内部全量埋点（recordRagRetrieval），无需重复采集。
 *
 * <p>降级：检索失败或证据不足 → fail（走步骤重试/重规划），不允许 LLM 在空上下文上继续。
 */
@Slf4j
@Component
public class RagStepExecutor implements StepExecutor {

    private final EnhancedRagService enhancedRagService;

    public RagStepExecutor(EnhancedRagService enhancedRagService) {
        this.enhancedRagService = enhancedRagService;
    }

    @Override
    public String executorPrefix() {
        return "rag_search";
    }

    @Override
    public StepExecutorResult execute(String executorId, Map<String, String> inputs,
                                      AgentExecutionContext ctx) {
        long started = System.currentTimeMillis();
        String query = firstNonBlank(inputs.get("query"), inputs.get("keywords"), ctx.getGoal());
        if (query == null || query.isBlank()) {
            return StepExecutorResult.fail("rag_search: 查询为空", System.currentTimeMillis() - started);
        }
        try {
            RagRetrievalSnapshot snapshot = enhancedRagService.retrieveSnapshot(
                    query, extractUserId(ctx));
            ctx.putVariable("rag." + ctx.getCurrentStepId() + ".snapshot", snapshot);
            if (snapshot == null || !snapshot.evidenceSufficient()
                    || snapshot.documents().isEmpty()) {
                String reason = snapshot != null && !snapshot.insufficiencyReason().isBlank()
                        ? snapshot.insufficiencyReason() : "no_retrieved_evidence";
                log.warn("[RAG-SEARCH] insufficient evidence taskId={} step={} reason={}",
                        ctx.getTaskId(), ctx.getCurrentStepId(), reason);
                return StepExecutorResult.fail("rag_search: evidence insufficient (" + reason + ")",
                        System.currentTimeMillis() - started);
            }
            String output = snapshot.context();
            log.info("[RAG-SEARCH] taskId={} step={} query='{}' docs={}",
                    ctx.getTaskId(), ctx.getCurrentStepId(), query, snapshot.documents().size());
            return StepExecutorResult.ok(output, 0, System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("[RAG-SEARCH] failed taskId={} query='{}': {}", ctx.getTaskId(), query, e.getMessage());
            return StepExecutorResult.fail("rag_search failed: " + e.getMessage(),
                    System.currentTimeMillis() - started);
        }
    }

    private Long extractUserId(AgentExecutionContext ctx) {
        Object uidObj = ctx.getVariable("userId");
        if (uidObj instanceof Long l) {
            return l;
        }
        if (uidObj instanceof Number n) {
            return n.longValue();
        }
        if (uidObj instanceof String s) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

}
