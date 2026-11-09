package com.yin.ai.platform.agent.pattern.context;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.memory.context.ContextFragment;
import com.yin.ai.platform.memory.context.ContextRequest;
import com.yin.ai.platform.memory.context.MemoryContextProvider;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import com.yin.ai.platform.gateway.GatewayTextClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文管理器 — 步骤间数据传递的核心引擎。
 * <p>
 * 职责：在步骤执行前，根据 StepContract 解析所有输入绑定，
 * 组装为结构化的 Map<String, String>，供 StepExecutor 注入 Prompt。
 * <p>
 * 解析策略：
 * - INLINE：直接从 ContextStore 读取前序输出，全量注入
 * - SUMMARY：读取前序输出，LLM 摘要后注入（节省 token）
 * - REFERENCE：仅注入引用标识 "[ref:stepId:key]"
 * - MEMORY：从 MemoryRuntime 检索相关知识注入
 * <p>
 * Token 预算：总注入量不超过 maxInputTokens，超出时自动降级
 * （INLINE → SUMMARY → REFERENCE）。
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);
    private static final int DEFAULT_MAX_INPUT_TOKENS = 8000;
    private static final int CHARS_PER_TOKEN = 2;  // 中文约 2 字符/token

    private final GatewayTextClient chatClient;
    private final int maxInputTokens;
    private final MemoryRuntime memoryRuntime;
    private final MemoryContextProvider memoryContextProvider;
    private final ObjectProvider<OnlineQualityRecorder> onlineQualityRecorderProvider;

    /** 兼容无 Memory 的构造（测试/降级场景）。 */
    public ContextManager(GatewayTextClient chatClient) {
        this(chatClient, DEFAULT_MAX_INPUT_TOKENS);
    }

    public ContextManager(GatewayTextClient chatClient, int maxInputTokens) {
        this(chatClient, maxInputTokens, null, null, null);
    }

    public ContextManager(GatewayTextClient chatClient, int maxInputTokens,
                          MemoryRuntime memoryRuntime,
                          ObjectProvider<OnlineQualityRecorder> onlineQualityRecorderProvider) {
        this(chatClient, maxInputTokens, memoryRuntime, onlineQualityRecorderProvider, null);
    }

    public ContextManager(GatewayTextClient chatClient, int maxInputTokens,
                          MemoryRuntime memoryRuntime,
                          ObjectProvider<OnlineQualityRecorder> onlineQualityRecorderProvider,
                          MemoryContextProvider memoryContextProvider) {
        this.chatClient = chatClient;
        this.maxInputTokens = maxInputTokens;
        this.memoryRuntime = memoryRuntime;
        this.onlineQualityRecorderProvider = onlineQualityRecorderProvider;
        this.memoryContextProvider = memoryContextProvider;
    }

    /**
     * 解析步骤契约，组装执行输入。
     *
     * @param contract 步骤契约
     * @param ctx      执行上下文
     * @return 解析后的输入 Map（key → 解析后的文本值）
     */
    public Map<String, String> resolve(StepContract contract, AgentExecutionContext ctx) {
        Map<String, String> resolved = new HashMap<>();
        int tokenBudget = maxInputTokens * CHARS_PER_TOKEN;
        int usedChars = 0;

        for (InputBinding binding : contract.inputs()) {
            String value = resolveBinding(binding, ctx, tokenBudget - usedChars);
            if (value != null && !value.isEmpty()) {
                resolved.put(binding.key(), value);
                usedChars += value.length();
            }
        }

        log.debug("[CTX-MGR] step={} resolved {} inputs, ~{} chars",
                contract.stepId(), resolved.size(), usedChars);
        return resolved;
    }

    private String resolveBinding(InputBinding binding, AgentExecutionContext ctx, int remainingBudget) {
        return switch (binding.mode()) {
            case INLINE -> resolveInline(binding, ctx, remainingBudget);
            case SUMMARY -> resolveSummary(binding, ctx, remainingBudget);
            case REFERENCE -> resolveReference(binding);
            case MEMORY -> resolveMemory(binding, ctx, remainingBudget);
        };
    }

    private String resolveInline(InputBinding binding, AgentExecutionContext ctx, int budget) {
        String raw = getStepOutput(binding.sourceStepId(), ctx);
        if (raw == null) return "";

        // 超预算时自动降级为 SUMMARY
        if (raw.length() > budget) {
            log.info("[CTX-MGR] inline too long ({}), degrading to summary", raw.length());
            return summarize(raw, budget);
        }
        return raw;
    }

    private String resolveSummary(InputBinding binding, AgentExecutionContext ctx, int budget) {
        if ("artifactKey".equals(binding.key())) {
            Object key = ctx.getVariable("artifact." + binding.sourceStepId() + ".key");
            return key != null ? key.toString() : "";
        }
        String raw = getStepOutput(binding.sourceStepId(), ctx);
        if (raw == null) return "";
        return summarize(raw, budget);
    }

    private String resolveReference(InputBinding binding) {
        return "[ref:" + binding.sourceStepId() + ":" + binding.key() + "]";
    }

    private String resolveMemory(InputBinding binding, AgentExecutionContext ctx, int budget) {
        if (memoryRuntime == null && memoryContextProvider == null) {
            return "";
        }
        String query = binding.memoryQuery() != null
                ? binding.memoryQuery().replace("{goal}", ctx.getGoal())
                : ctx.getGoal();
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            int topK = binding.memoryTopK() > 0 ? binding.memoryTopK() : 5;
            Long userId = extractUserId(ctx);
            MemoryQuery memoryQuery = MemoryQuery.byText(query, topK);
            if (binding.memoryType() != null) {
                memoryQuery = memoryQuery.withTypeFilter(binding.memoryType());
            }
            if (memoryContextProvider != null) {
                MemoryAccessContext accessContext = accessContext(ctx);
                ContextFragment fragment = memoryContextProvider.provide(new ContextRequest(
                        memoryQuery, accessContext, Math.max(1, budget / CHARS_PER_TOKEN)));
                ctx.putVariable("agent.memoryFragment", fragment);
                log.debug("[CTX-MGR] memory provider: type={} query='{}' tokens={}",
                        binding.memoryType(), query, fragment.tokenCount());
                return fragment.content();
            }
            QueryResult result = memoryRuntime.retrieve(memoryQuery,
                    new MemoryContext(null, null, userId, "agent", "ctx.memory",
                            Instant.now(), Map.of()));

            // 与 ReAct 路径一致：召回结果写入 ctx 变量，供后续阶段引用
            ctx.putVariable("agent.recallResult", result);

            // 在线记忆召回质量采集（异步，失败不阻塞）
            if (onlineQualityRecorderProvider != null) {
                try {
                    OnlineQualityRecorder recorder = onlineQualityRecorderProvider.getIfAvailable();
                    if (recorder != null) {
                        recorder.recordMemoryRecall(userId, query, result);
                    }
                } catch (Exception e) {
                    log.debug("[CTX-MGR] memory recall metric failed (non-blocking): {}", e.getMessage());
                }
            }

            List<ScoredMemoryCell> cells = result == null || result.results() == null
                    ? List.of() : result.results();
            if (cells.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("以下是与你相关的历史记忆（可帮助回答当前问题）：\n");
            int used = sb.length();
            for (int i = 0; i < Math.min(cells.size(), 5); i++) {
                ScoredMemoryCell cell = cells.get(i);
                String line = "【记忆" + (i + 1) + "】" + cell.cell().content() + "\n";
                if (used + line.length() > budget) {
                    break;
                }
                sb.append(line);
                used += line.length();
            }
            log.debug("[CTX-MGR] memory recall: type={} query='{}' recalled={}",
                    binding.memoryType(), query, cells.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("[CTX-MGR] memory recall failed (non-blocking): {}", e.getMessage());
            return "";
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

    private MemoryAccessContext accessContext(AgentExecutionContext ctx) {
        String userId = ctx.getUserId();
        String tenantId = value(ctx.getVariable("tenantId"));
        if (tenantId == null) tenantId = userId;
        return new MemoryAccessContext(tenantId, userId, ctx.getSessionId(), ctx.getTaskId(),
                value(ctx.getVariable("workflowId")),
                ctx.getAgentMode() != null ? ctx.getAgentMode().name() : null,
                ctx.getCurrentStepId(), stringSet(ctx.getVariable("roles")),
                stringSet(ctx.getVariable("permissions")), value(ctx.getVariable("traceId")));
    }

    private String value(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return Set.of();
        java.util.HashSet<String> result = new java.util.HashSet<>();
        iterable.forEach(item -> { if (item != null) result.add(item.toString()); });
        return Set.copyOf(result);
    }

    private String getStepOutput(String stepId, AgentExecutionContext ctx) {
        if (stepId == null) return null;
        Object val = ctx.getVariable("step." + stepId + ".output");
        return val != null ? val.toString() : null;
    }

    private String summarize(String text, int budget) {
        if (text.length() <= budget) return text;

        try {
            String prompt = "请将以下内容压缩为不超过 " + (budget / CHARS_PER_TOKEN)
                    + " token 的摘要，保留关键信息：\n\n" + text;
            String summary = chatClient.generate("agent:context-summary", prompt, null, 0.1)
                    .getContent();
            return summary != null ? summary : text.substring(0, budget);
        } catch (Exception e) {
            log.warn("[CTX-MGR] summarize failed, truncating: {}", e.getMessage());
            return text.substring(0, Math.min(budget, text.length()));
        }
    }
}
