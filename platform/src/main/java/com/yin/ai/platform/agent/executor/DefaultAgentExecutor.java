package com.yin.ai.platform.agent.executor;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentEvent;
import com.yin.ai.platform.agent.AgentEventType;
import com.yin.ai.platform.agent.StructuredOutputContract;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.trace.StepTraceRecorder;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.function.FunctionResult;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import com.yin.ai.platform.rag.grounding.RagGroundingGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认 Agent 执行器 — 通过 FunctionRegistry 调用工具或通过 UnifiedChatClient 调用 LLM。
 * <p>
 * 工具步骤（{@code step.tool() != null}）走 {@link FunctionRegistry#invoke}，
 * LLM 步骤（{@code step.tool() == null}）走 {@link UnifiedChatClient#chat}。
 * <p>
 * 通过 {@code ctx.getVariable("toolFilter")} 白名单限制 SubAgent 可用工具（继承 D3 设计）。
 */
public class DefaultAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentExecutor.class);

    private final FunctionRegistry functionRegistry;
    private final UnifiedChatClient chatClient;
    private final StepTraceRecorder stepTraceRecorder;
    private final RagGroundingGuard groundingGuard;

    public DefaultAgentExecutor(FunctionRegistry functionRegistry, UnifiedChatClient chatClient) {
        this(functionRegistry, chatClient, StepTraceRecorder.NOOP, null);
    }

    public DefaultAgentExecutor(FunctionRegistry functionRegistry, UnifiedChatClient chatClient,
                                StepTraceRecorder stepTraceRecorder) {
        this(functionRegistry, chatClient, stepTraceRecorder, null);
    }

    public DefaultAgentExecutor(FunctionRegistry functionRegistry, UnifiedChatClient chatClient,
                                StepTraceRecorder stepTraceRecorder,
                                RagGroundingGuard groundingGuard) {
        this.functionRegistry = functionRegistry;
        this.chatClient = chatClient;
        this.stepTraceRecorder = stepTraceRecorder != null ? stepTraceRecorder : StepTraceRecorder.NOOP;
        this.groundingGuard = groundingGuard;
    }

    @Override
    public StepResult execute(Step step, AgentContext ctx) {
        long start = System.currentTimeMillis();

        try {
            StepResult result;
            boolean isTool = step.tool() != null && !step.tool().isBlank();
            if (isTool) {
                result = executeTool(step, ctx, start);
                // [全链路追踪] 工具调用环节埋点
                stepTraceRecorder.record(ctx.getExecCtx().traceId(), "TOOL_CALL",
                        step.tool() + " params=" + briefParams(step.toolParams()),
                        System.currentTimeMillis() - start);
            } else {
                result = executeLLM(step, ctx, start);
                // [全链路追踪] LLM 调用环节埋点
                stepTraceRecorder.record(ctx.getExecCtx().traceId(), "LLM_CALL",
                        "step=" + step.id() + " desc=" + brief(step.description()),
                        System.currentTimeMillis() - start);
            }
            return result;
        } catch (Exception e) {
            log.error("[EXECUTOR] step={} error={}", step.id(), e.getMessage(), e);
            return StepResult.fail(e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private static String briefParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        String s = String.valueOf(params);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private StepResult executeTool(Step step, AgentContext ctx, long start) {
        @SuppressWarnings("unchecked")
        Set<String> filter = ctx.getVariable("toolFilter", Set.class);
        if (filter != null && !filter.contains(step.tool())) {
            String msg = "tool not in whitelist: " + step.tool();
            log.warn("[EXECUTOR] step={} {}", step.id(), msg);
            return StepResult.fail(msg, System.currentTimeMillis() - start);
        }

        log.info("[EXECUTOR] step={} invoking tool={} params={}", step.id(), step.tool(), step.toolParams());

        FunctionResult fnResult = functionRegistry.invoke(step.tool(), step.toolParams());

        String output = fnResult.getData() != null ? fnResult.getData().toString() : "";
        ctx.addMessage("assistant", "[" + step.tool() + "] " + output);

        return fnResult.isSuccess()
                ? StepResult.ok(output, System.currentTimeMillis() - start)
                : StepResult.fail(fnResult.getErrorMessage() != null
                        ? fnResult.getErrorMessage() : "unknown error",
                        System.currentTimeMillis() - start);
    }

    private StepResult executeLLM(Step step, AgentContext ctx, long start) {
        List<GatewayMessage> messages = buildMessages(ctx, step);

        GatewayRequest request = new GatewayRequest();
        request.setMessages(messages);
        request.setCaller("agent.executor");
        request.setTraceId(ctx.getExecCtx().traceId());

        GatewayOptions options = new GatewayOptions();
        options.setEnableFunctionCalling(false);
        StructuredOutputContract contract = ctx.getConfig().outputContract();
        if (contract != null) {
            // 结构化输出契约：注册 capture 工具但不强制 tool_choice，
            // 模型完成任务后调用 {toolName} 提交最终结果
            options.setJsonResponse(false);
            options.setForcedToolName(contract.toolName());
            options.setOutputSchema(contract.jsonSchema());
        } else {
            options.setJsonResponse(ctx.getConfig().jsonResponse());
        }
        // Model selection is independent from reasoning. Provider-specific
        // parameter incompatibilities are handled by the adapter.
        Object modelOverride = ctx.getVariable("model");
        if (modelOverride instanceof String m && !m.isBlank()) {
            options.setModel(m);
            log.info("[EXECUTOR] step={} model override: {}", step.id(), m);
        }
        options.setTemperature(resolveTemperature(ctx));
        if (ctx.getVariable("modelExecutionOptions") instanceof ModelExecutionOptions executionOptions) {
            options.setModelExecutionOptions(executionOptions);
        }
        request.setOptions(options);

        log.info("[EXECUTOR] step={} calling LLM with {} messages", step.id(), messages.size());

        // 流式模式：FluxSink 存在且非 JSON/契约模式时，使用 chatStream 逐 token 推送
        @SuppressWarnings("unchecked")
        FluxSink<AgentEvent> fluxSink = ctx.getVariable("agent.fluxSink", FluxSink.class);
        if (fluxSink != null && !ctx.getConfig().jsonResponse() && contract == null) {
            return executeLLMStream(step, ctx, request, fluxSink, start);
        }

        // 同步模式（JSON 模式、契约模式或无 FluxSink）
        GatewayResponse response;
        try {
            response = ExecutionContextHolder.runWith(ctx.getExecCtx(), () -> chatClient.chat(request));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String content = response.getContent() != null ? response.getContent() : "";
        if (contract != null && response.getToolCallArguments() != null
                && !response.getToolCallArguments().isBlank()) {
            // 结构化输出：工具参数即结果 JSON
            content = response.getToolCallArguments();
            log.info("[EXECUTOR] step={} structured output via {}", step.id(), contract.toolName());
        }
        accumulateTokens(ctx, response.getTokenUsage());
        content = applyGroundingGuard(ctx, request, content);
        ctx.addMessage("assistant", content);

        return StepResult.ok(content, System.currentTimeMillis() - start);
    }

    /**
     * 流式 LLM 调用 — 逐 token 发射 TEXT_CHUNK 事件到 FluxSink，实现打字机效果。
     *
     * <p>通过 {@code chatClient.chatStream(request)} 获取 token 流，
     * 每个 text 类型的 StreamPacket 映射为 {@code AgentEventType.TEXT_CHUNK} 事件，
     * 经 ReActLoop.enterStream() 中注册的 progressCallback 实时推送到前端 SSE。
     */
    @SuppressWarnings("unchecked")
    private StepResult executeLLMStream(Step step, AgentContext ctx, GatewayRequest request,
                                        FluxSink<AgentEvent> fluxSink, long start) {
        StringBuilder accumulated = new StringBuilder();
        boolean bufferForGrounding = shouldValidateGrounding(ctx);

        Flux<StreamPacket> tokenStream;
        try {
            tokenStream = ExecutionContextHolder.runWith(
                    ctx.getExecCtx(), () -> chatClient.chatStream(request));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        tokenStream
                .doOnNext(packet -> {
                    int stepIdx = parseStepIndex(step.id());
                    if ("text".equals(packet.getType()) && packet.getContent() instanceof String chunk) {
                        accumulated.append(chunk);
                        // RAG 最终答案先缓冲，证据门禁通过后再发射，避免把幻觉 token 提前推给客户端。
                        if (!bufferForGrounding) {
                            fluxSink.next(AgentEvent.of(AgentEventType.TEXT_CHUNK, stepIdx, chunk));
                        }
                    } else if ("reasoning".equals(packet.getType())) {
                        // Raw chain-of-thought remains provider-internal.
                    }
                })
                .blockLast();

        String content = applyGroundingGuard(ctx, request, accumulated.toString());
        if (bufferForGrounding) {
            fluxSink.next(AgentEvent.of(AgentEventType.TEXT_CHUNK,
                    parseStepIndex(step.id()), content));
        }
        ctx.addMessage("assistant", content);
        log.info("[EXECUTOR] step={} stream complete, {} chars", step.id(), content.length());

        return StepResult.ok(content, System.currentTimeMillis() - start);
    }

    private List<GatewayMessage> buildMessages(AgentContext ctx, Step step) {
        List<GatewayMessage> messages = new ArrayList<>();

        // 从 AgentTask context 注入 System Prompt（Skill 渲染结果）
        Object systemPrompt = ctx.getVariable("systemPrompt");
        if (systemPrompt instanceof String sp && !sp.isBlank()) {
            messages.add(GatewayMessage.system(sp));
        }

        // 注入 RAG 上下文（知识库检索结果），使 LLM 能基于知识库回答问题
        Object ragContext = ctx.getVariable("agent.ragContext");
        if (ragContext instanceof String rc && !rc.isBlank()) {
            messages.add(GatewayMessage.system("【证据约束】以下是知识库证据。"
                    + "事实性结论必须紧跟 [E编号]；只能引用存在的编号；"
                    + "不得把常识或模型记忆伪装成证据。资料不足或冲突时必须明确拒绝下结论。\n" + rc));
        }

        // ── 上下文截断：防止长任务 token 溢出 ──
        List<String> rawMessages = ctx.getMessages();
        List<String> truncatedMessages = truncateHistory(rawMessages);

        for (String entry : truncatedMessages) {
            int colon = entry.indexOf(':');
            if (colon > 0) {
                String role = entry.substring(0, colon);
                String content = entry.substring(colon + 1);
                messages.add(switch (role) {
                    case "system" -> GatewayMessage.system(content);
                    case "assistant" -> GatewayMessage.assistant(content);
                    default -> GatewayMessage.user(content);
                });
            }
        }

        // JSON 模式：将原始 goal（含 JSON Schema）作为持久化格式约束注入，
        // 防止多轮 ReAct 后初始提示词丢失导致 LLM 输出格式漂移
        if (ctx.getConfig().jsonResponse()) {
            String goal = ctx.getTask().goal();
            if (goal != null && !goal.isBlank()) {
                messages.add(GatewayMessage.system("【输出格式约束 — 必须严格遵守】\n" + goal));
            }
        }

        // 结构化输出契约：完成任务后调用 {toolName} 工具提交最终结果（工具参数即结果 JSON）
        StructuredOutputContract contract = ctx.getConfig().outputContract();
        if (contract != null) {
            messages.add(GatewayMessage.system("【输出契约】完成任务后必须调用 "
                    + contract.toolName() + " 工具提交最终结果（工具参数即为结果 JSON），"
                    + "不要输出任何文字或 Markdown。"));
        }

        messages.add(GatewayMessage.user(step.description()));
        return messages;
    }

    @SuppressWarnings("unchecked")
    private boolean shouldValidateGrounding(AgentContext ctx) {
        if (groundingGuard == null || !groundingGuard.enabled()) {
            return false;
        }
        Boolean finalStep = ctx.getVariable("agent.finalStep", Boolean.class);
        if (!Boolean.TRUE.equals(finalStep)) {
            return false;
        }
        if (ctx.getConfig().jsonResponse() || ctx.getConfig().outputContract() != null) {
            return false;
        }
        return Boolean.TRUE.equals(ctx.getVariable("agent.ragTriggered", Boolean.class));
    }

    @SuppressWarnings("unchecked")
    private String applyGroundingGuard(AgentContext ctx, GatewayRequest originalRequest,
                                       String answer) {
        if (!shouldValidateGrounding(ctx)) {
            return answer;
        }
        List<RetrievedDocument> documents = ctx.getVariable("agent.ragDocuments", List.class);
        if (documents == null) {
            documents = List.of();
        }
        boolean evidenceSufficient = Boolean.TRUE.equals(
                ctx.getVariable("agent.ragEvidenceSufficient", Boolean.class));
        RagGroundingGuard.ValidationResult validation = groundingGuard.validate(
                ctx.getTask().goal(), answer, documents, evidenceSufficient);
        String current = answer;

        for (int attempt = 0;
             !validation.valid() && evidenceSufficient && !documents.isEmpty()
                     && attempt < groundingGuard.maxRegenerationAttempts();
             attempt++) {
            log.warn("[GROUNDING] validation failed reason={} faithfulness={}, regenerating attempt={}",
                    validation.reason(), validation.faithfulness(), attempt + 1);
            GatewayRequest retry = new GatewayRequest();
            List<GatewayMessage> retryMessages = new ArrayList<>(originalRequest.getMessages());
            retryMessages.add(GatewayMessage.assistant(current));
            retryMessages.add(GatewayMessage.system("上一个答案未通过证据校验（原因："
                    + validation.reason() + "）。请重新回答：删除所有证据未支持的事实，"
                    + "每个保留的事实结论引用有效的 [E编号]；若证据不足，直接说明无法确定。"));
            retry.setMessages(retryMessages);
            retry.setOptions(originalRequest.getOptions());
            retry.setTraceId(originalRequest.getTraceId());
            retry.setCaller("agent.grounding-regeneration");
            try {
                GatewayResponse response = ExecutionContextHolder.runWith(
                        ctx.getExecCtx(), () -> chatClient.chat(retry));
                current = response.getContent() != null ? response.getContent() : "";
                accumulateTokens(ctx, response.getTokenUsage());
                validation = groundingGuard.validate(
                        ctx.getTask().goal(), current, documents, evidenceSufficient);
            } catch (Exception e) {
                log.warn("[GROUNDING] regeneration failed: {}", e.getMessage());
                break;
            }
        }

        if (!validation.valid()) {
            exposeGroundingQuality(ctx, validation);
            stepTraceRecorder.record(ctx.getExecCtx().traceId(), "RAG_GROUNDING_REJECTED",
                    "reason=" + validation.reason() + " faithfulness=" + validation.faithfulness()
                            + " claimCitationCoverage=" + validation.claimCitationCoverage(), 0);
            return groundingGuard.refusal();
        }
        exposeGroundingQuality(ctx, validation);
        stepTraceRecorder.record(ctx.getExecCtx().traceId(), "RAG_GROUNDING_PASSED",
                "faithfulness=" + validation.faithfulness()
                        + " citations=" + validation.citations().size()
                        + " claimCitationCoverage=" + validation.claimCitationCoverage(), 0);
        return current;
    }

    private void exposeGroundingQuality(AgentContext ctx,
                                        RagGroundingGuard.ValidationResult validation) {
        if (ctx == null || validation == null) return;
        ctx.putVariable("quality.claimCount", validation.claimCount());
        ctx.putVariable("quality.supportedClaimCount", validation.supportedClaimCount());
        if (!Double.isNaN(validation.claimCitationCoverage())) {
            ctx.putVariable("quality.claimCitationCoverage", validation.claimCitationCoverage());
        }
        if (!Double.isNaN(validation.faithfulness())) {
            ctx.putVariable("quality.faithfulness", validation.faithfulness());
        }
        if (!Double.isNaN(validation.answerRelevance())) {
            ctx.putVariable("quality.answerRelevance", validation.answerRelevance());
        }
        if (!Double.isNaN(validation.evidenceSupportRate())) {
            ctx.putVariable("quality.evidenceSupportRate", validation.evidenceSupportRate());
        }
        if (!Double.isNaN(validation.conflictRate())) {
            ctx.putVariable("quality.conflictRate", validation.conflictRate());
        }
        if (!Double.isNaN(validation.unsupportedFactRate())) {
            ctx.putVariable("quality.unsupportedFactRate", validation.unsupportedFactRate());
        }
        ctx.putVariable("quality.groundingStatus", validation.valid() ? "PASSED" : "REJECTED");
        if (!validation.valid()) ctx.putVariable("quality.groundingReason", validation.reason());
    }

    /**
     * 截断消息历史，防止 token 超限。
     * <p>
     * 策略：保留最近 KEEP_RECENT 条消息原样，更早的工具输出压缩到 MAX_TOOL_OUTPUT 字符，
     * LLM 输出压缩到 MAX_LLM_OUTPUT 字符。System 消息始终保留。
     */
    private static final int MAX_HISTORY_CHARS = 12000;
    private static final int KEEP_RECENT = 6;
    private static final int MAX_TOOL_OUTPUT = 500;
    private static final int MAX_LLM_OUTPUT = 800;

    private List<String> truncateHistory(List<String> messages) {
        if (messages.size() <= KEEP_RECENT + 2) {
            return messages;
        }

        // 估算总字符数
        int totalChars = messages.stream().mapToInt(String::length).sum();
        if (totalChars <= MAX_HISTORY_CHARS) {
            return messages;
        }

        log.info("[EXECUTOR] history truncation triggered: {} messages, ~{} chars", messages.size(), totalChars);

        List<String> result = new ArrayList<>();
        int keepFrom = Math.max(0, messages.size() - KEEP_RECENT);

        for (int i = 0; i < messages.size(); i++) {
            String entry = messages.get(i);

            if (i < keepFrom) {
                // 旧消息：压缩工具输出和 LLM 输出
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    String role = entry.substring(0, colon);
                    String content = entry.substring(colon + 1);

                    if ("assistant".equals(role) && content.startsWith("[") && content.contains("]")) {
                        // 工具输出：[toolName] output → [toolName] <truncated>
                        int bracketEnd = content.indexOf(']');
                        String toolPrefix = content.substring(0, bracketEnd + 1);
                        if (content.length() > MAX_TOOL_OUTPUT) {
                            result.add(role + ":" + toolPrefix + " " +
                                    content.substring(bracketEnd + 1, bracketEnd + 1 + MAX_TOOL_OUTPUT) + "...(已截断)");
                        } else {
                            result.add(entry);
                        }
                    } else if ("assistant".equals(role) && content.length() > MAX_LLM_OUTPUT) {
                        // LLM 长输出截断
                        result.add(role + ":" + content.substring(0, MAX_LLM_OUTPUT) + "...(已截断)");
                    } else {
                        result.add(entry);
                    }
                } else {
                    result.add(entry);
                }
            } else {
                // 近期消息：保留原样
                result.add(entry);
            }
        }

        return result;
    }

    private void accumulateTokens(AgentContext ctx, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        accumulateTokenCounter(ctx, "agent.promptTokens", usage.getPromptTokens());
        accumulateTokenCounter(ctx, "agent.completionTokens", usage.getCompletionTokens());
        Integer total = usage.getTotalTokens();
        if (total == null && (usage.getPromptTokens() != null || usage.getCompletionTokens() != null)) {
            total = Math.max(0, usage.getPromptTokens() != null ? usage.getPromptTokens() : 0)
                    + Math.max(0, usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
        }
        accumulateTokenCounter(ctx, "agent.totalTokens", total);
    }

    private void accumulateTokenCounter(AgentContext ctx, String key, Integer addition) {
        if (addition == null) return;
        Integer current = ctx.getVariable(key, Integer.class);
        long updated = (long) (current != null ? current : 0) + Math.max(0, addition);
        ctx.putVariable(key, updated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) updated);
    }

    /**
     * 从上下文解析模型温度 — BaseReactAgent 通过 context 变量 "temperature" 注入。
     * 未注入时使用默认值 0.3（与改动前行为一致）。
     */
    private static double resolveTemperature(AgentContext ctx) {
        Object temp = ctx.getVariable("temperature");
        if (temp instanceof Number n) {
            return n.doubleValue();
        }
        return 0.3;
    }

    private static int parseStepIndex(String stepId) {
        if (stepId == null) return 0;
        try {
            return Integer.parseInt(stepId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
