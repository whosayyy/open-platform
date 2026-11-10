package com.yin.ai.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.engine.Task;
import com.yin.ai.platform.engine.TaskManager;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.observe.AiMetrics;
import com.yin.ai.platform.observe.RouterFirstMonitor;
import com.yin.ai.platform.knowledge.KnowledgeRegistry;
import com.yin.ai.platform.runtime.postprocess.ResponsePostProcessorChain;
import com.yin.ai.platform.adapter.skill.composer.PromptComposer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Skill Runtime 实现 — 通用 Skill 执行引擎。
 * <p>
 * V2.1 升级：支持多 Skill 执行，通过 {@link PromptComposer} 动态组合 System Prompt。
 * 不含任何业务特定逻辑，所有业务差异由 .md Skill 文件 + SkillSelector 表达。
 * <p>
 * 执行流程：
 * <ol>
 *   <li>SkillLoader 加载所有 SkillManifest</li>
 *   <li>PromptComposer 按优先级 + 依赖顺序拼接 System Prompt</li>
 *   <li>构建 GatewayMessages + GatewayOptions</li>
 *   <li>调用 UnifiedChatClient</li>
 * </ol>
 */
@Slf4j
public class DefaultSkillRuntime implements SkillRuntime {

    private final SkillLoader skillLoader;
    private final UnifiedChatClient gateway;
    private final FunctionRegistry functionRegistry;
    private final PromptComposer promptComposer;
    private final KnowledgeRegistry knowledgeRegistry;
    private final ResponsePostProcessorChain postProcessorChain;
    private final SessionMemoryManager sessionMemoryManager;
    private final ConversationManager conversationManager;
    private final ResponseParser responseParser;
    private final AiMetrics aiMetrics;
    private final Map<String, Boolean> activeStreams = new ConcurrentHashMap<>();
    private final GatewayProperties gatewayProperties;
    private final SinkStreamProducer streamProducer;
    private final TaskManager taskManager;
    private RouterFirstMonitor routerFirstMonitor;

    public DefaultSkillRuntime(
            SkillLoader skillLoader,
            UnifiedChatClient gateway,
            FunctionRegistry functionRegistry,
            SessionMemoryManager sessionMemoryManager,
            ObjectMapper objectMapper,
            PromptComposer promptComposer,
            KnowledgeRegistry knowledgeRegistry,
            ResponsePostProcessorChain postProcessorChain,
            AiMetrics aiMetrics,
            GatewayProperties gatewayProperties,
            SinkStreamProducer streamProducer,
            TaskManager taskManager) {
        this.skillLoader = skillLoader;
        this.gateway = gateway;
        this.functionRegistry = functionRegistry;
        this.promptComposer = promptComposer;
        this.knowledgeRegistry = knowledgeRegistry;
        this.postProcessorChain = postProcessorChain != null ? postProcessorChain : new ResponsePostProcessorChain(List.of());
        this.sessionMemoryManager = sessionMemoryManager;
        this.conversationManager = new ConversationManager(sessionMemoryManager);
        this.responseParser = new ResponseParser(objectMapper);
        this.aiMetrics = aiMetrics;
        this.gatewayProperties = gatewayProperties;
        this.streamProducer = streamProducer;
        this.taskManager = taskManager;
    }

    @Autowired(required = false)
    public void setRouterFirstMonitor(RouterFirstMonitor routerFirstMonitor) {
        this.routerFirstMonitor = routerFirstMonitor;
    }

    // ——— 多 Skill 执行（V2.1 主入口） ———

    @Override
    public SkillResult execute(List<String> skillNames, SkillExecutionContext ctx) {
        if (routerFirstMonitor != null) routerFirstMonitor.recordSkill();
        long start = System.currentTimeMillis();
        log.info("[RUNTIME] skills={} status=START sessionId={}", skillNames, ctx.getSessionId());

        // 1. 加载所有 Manifest
        List<SkillManifest> manifests = new ArrayList<>();
        for (String name : skillNames) {
            SkillManifest manifest = skillLoader.load(name)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + name));
            manifests.add(manifest);
        }

        SkillManifest primary = manifests.get(manifests.size() - 1);

        try {
            // 2. 确定 RAG 上下文
            String ragContext = resolveRagContext(primary, ctx);

            // 3. 组合 Prompt（注入 ragContext）
            String systemPrompt = promptComposer.compose(manifests, ctx, ragContext);

            // 4. 构建 GatewayMessages（含历史对话）
            List<GatewayMessage> messages = buildMessagesWithHistory(
                    primary, ctx, systemPrompt);

            // 5. 收集 Function 名称（所有 Manifest 的 tools 并集）
            Set<String> functionNames = collectFunctionNames(manifests);
            if (Boolean.TRUE.equals(ctx.getAttributes().get(SkillExecutionContext.ATTR_SKIP_TOOLS))) {
                functionNames = Set.of();
            }

            // 6. 构建 GatewayOptions（primary Skill 决定 model/temperature）
            GatewayOptions options = buildOptions(primary, !functionNames.isEmpty());

            // 7. 调用 AI Gateway（绑定 ExecutionContext，满足 RetryFilter 等需求）
            GatewayRequest request = new GatewayRequest();
            request.setMessages(messages);
            request.setFunctionNames(functionNames);
            request.setOptions(options);
            request.setTraceId(ctx.getTraceId());
            request.setCaller(primary.getName());

            ExecutionContext engineCtx = ExecutionContext.fromCurrentRequest()
                    .withCaller(primary.getName())
                    .withOperation("execute");
            GatewayResponse response = ExecutionContextHolder.runWith(engineCtx,
                    () -> gateway.chat(request));

            // 7.1 Response 后处理
            String processedContent = postProcessorChain.process(response.getContent(), ctx);

            // 8. 记录本轮对话（供后续请求复用）
            if (primary.getMemory() != null && primary.getMemory().isEnabled()
                    && ctx.getSessionId() != null) {
                conversationManager.recordUserMessage(ctx.getSessionId(), ctx.getUserInput());
                if (processedContent != null) {
                    conversationManager.recordAssistantMessage(ctx.getSessionId(), processedContent);
                }
            }

            long duration = System.currentTimeMillis() - start;
            log.info("[RUNTIME] skills={} status=END durationMs={}", skillNames, duration);
            if (aiMetrics != null) {
                aiMetrics.recordRuntimeExecution(primary.getName(), "success", duration);
            }

            return SkillResult.builder()
                    .success(true)
                    .data(processedContent)
                    .gatewayResponse(response)
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[RUNTIME] skills={} status=FAIL durationMs={}", skillNames, duration, e);
            if (aiMetrics != null) {
                aiMetrics.recordRuntimeExecution(
                        skillNames.isEmpty() ? "unknown" : skillNames.get(skillNames.size() - 1),
                        "fail", duration);
            }

            String fallback = (primary.getRules() != null)
                    ? primary.getRules().getFallbackMessage() : "AI 服务暂时不可用";
            return SkillResult.builder().success(false).data(fallback).error(e).durationMs(duration).build();
        }
    }

    @Override
    public Flux<StreamPacket> executeStream(List<String> skillNames, SkillExecutionContext ctx) {
        if (routerFirstMonitor != null) routerFirstMonitor.recordSkill();
        // 加载所有 Manifest
        List<SkillManifest> manifests = new ArrayList<>();
        for (String name : skillNames) {
            SkillManifest manifest = skillLoader.load(name)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + name));
            manifests.add(manifest);
        }

        SkillManifest primary = manifests.get(manifests.size() - 1);

        // Create Task + initialize Sink
        Task task = taskManager.createTask(ctx.getTraceId());
        streamProducer.initialize(task.getTaskId());
        String taskId = task.getTaskId();
        activeStreams.put(taskId, true);

        log.info("[RUNTIME] skills={} status=STREAM_START sessionId={}", skillNames, ctx.getSessionId());

        // 1. 确定 RAG 上下文
        String ragContext = resolveRagContext(primary, ctx);

        // 2. 组合 Prompt（注入 ragContext）
        String systemPrompt = promptComposer.compose(manifests, ctx, ragContext);

        // 3. 构建 messages（含历史对话）
        List<GatewayMessage> messages = buildMessagesWithHistory(
                primary, ctx, systemPrompt);

        // 4. 收集 Function 名称
        Set<String> functionNames = collectFunctionNames(manifests);
        if (Boolean.TRUE.equals(ctx.getAttributes().get(SkillExecutionContext.ATTR_SKIP_TOOLS))) {
            functionNames = Set.of();
        }

        // 5. 构建 GatewayOptions
        GatewayOptions options = buildOptions(primary, !functionNames.isEmpty());
        options.setStreamEnabled(true);

        // 6. 构建 GatewayRequest
        GatewayRequest request = new GatewayRequest();
        request.setMessages(messages);
        request.setFunctionNames(functionNames);
        request.setOptions(options);
        request.setTraceId(ctx.getTraceId());
        request.setCaller(primary.getName());

        // 7. 调用流式接口
        ExecutionContext engineCtx = ExecutionContext.fromCurrentRequest()
                .withCaller(primary.getName())
                .withOperation("executeStream");
        Flux<StreamPacket> streamFlux;
        try {
            streamFlux = ExecutionContextHolder.runWith(engineCtx,
                    () -> gateway.chatStream(request));
        } catch (Exception e) {
            streamProducer.emitError(taskId, "AI 服务繁忙，请稍后重试");
            streamProducer.complete(taskId);
            return streamProducer.asFlux(taskId);
        }

        // 8. 捕获变量供 subscriber 回调使用
        boolean memoryEnabled = primary.getMemory() != null
                && primary.getMemory().isEnabled()
                && ctx.getSessionId() != null;
        String sessionId = ctx.getSessionId();
        String userInput = ctx.getUserInput();
        StringBuffer fullResponse = new StringBuffer();

        log.info("[RUNTIME] memoryEnabled={} sessionId={} memoryConfig={}",
                memoryEnabled, sessionId,
                primary.getMemory() != null ? primary.getMemory().isEnabled() : "null");

        // 9. Subscribe gateway Flux into Sink (async)
        streamFlux
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        packet -> {
                            if ("text".equals(packet.getType()) && packet.getContent() instanceof String text) {
                                fullResponse.append(text);
                                String processed = postProcessorChain.process(text, ctx);
                                streamProducer.emitText(taskId, processed);
                            }
                        },
                        error -> {
                            activeStreams.remove(taskId);
                            if (memoryEnabled) {
                                conversationManager.recordUserMessage(sessionId, userInput);
                                String partialReply = fullResponse.toString();
                                if (!partialReply.isEmpty()) {
                                    conversationManager.recordAssistantMessage(sessionId, partialReply);
                                    log.info("[RUNTIME] stream failed, saved partial response (len={}) for retry", partialReply.length());
                                }
                            }
                            log.error("[RUNTIME] skills={} status=STREAM_FAIL error={}",
                                    skillNames, error.getMessage());
                            streamProducer.emitError(taskId, "AI 服务繁忙，请稍后重试");
                            streamProducer.complete(taskId);
                        },
                        () -> {
                            activeStreams.remove(taskId);
                            log.info("[RUNTIME] doOnComplete fired, memoryEnabled={} fullResponseLen={}",
                                    memoryEnabled, fullResponse.length());
                            if (memoryEnabled) {
                                conversationManager.recordUserMessage(sessionId, userInput);
                                String assistantReply = fullResponse.toString();
                                if (!assistantReply.isEmpty()) {
                                    conversationManager.recordAssistantMessage(sessionId, assistantReply);
                                }
                            }
                            streamProducer.emitComplete(taskId, fullResponse.toString());
                            streamProducer.complete(taskId);
                            log.info("[RUNTIME] skills={} status=STREAM_END", skillNames);
                        }
                );

        // 10. Return Sink Flux immediately
        return streamProducer.asFlux(taskId);
    }

    // ——— 单 Skill 执行（向后兼容，委托到多 Skill 方法） ———

    @Override
    public SkillResult execute(String skillName, SkillExecutionContext ctx) {
        return execute(List.of(skillName), ctx);
    }

    @Override
    public Flux<StreamPacket> executeStream(String skillName, SkillExecutionContext ctx) {
        return executeStream(List.of(skillName), ctx);
    }

    @Override
    public void cancel(String taskId) {
        if (taskId != null) {
            activeStreams.remove(taskId);
            log.info("[RUNTIME] taskId={} status=CANCELLED", taskId);
        }
    }

    // ——— 内部方法 ———

    private String resolveRagContext(SkillManifest primary, SkillExecutionContext ctx) {
        if (knowledgeRegistry != null
                && primary.getKnowledge() != null
                && !primary.getKnowledge().isEmpty()
                && ctx.getUserInput() != null) {
            return knowledgeRegistry.retrieve(primary.getKnowledge(), ctx.getUserInput());
        }
        return "";
    }

    private Set<String> collectFunctionNames(List<SkillManifest> manifests) {
        Set<String> names = new HashSet<>();
        for (SkillManifest m : manifests) {
            if (m.getTools() != null) {
                names.addAll(m.getTools());
            }
        }
        return names;
    }

    private List<GatewayMessage> buildMessagesWithHistory(SkillManifest primary,
                                                            SkillExecutionContext ctx,
                                                            String systemPrompt) {
        List<GatewayMessage> messages = new ArrayList<>();
        messages.add(GatewayMessage.system(systemPrompt, true));

        if (primary.getMemory() != null && primary.getMemory().isEnabled()
                && ctx.getSessionId() != null) {
            List<SessionMemoryManager.ChatMessage> history;
            if (gatewayProperties != null
                    && gatewayProperties.getTokenBudget() != null
                    && gatewayProperties.getTokenBudget().isEnabled()) {
                int maxTokens = gatewayProperties.getTokenBudget().getMaxHistoryTokens();
                history = sessionMemoryManager.getHistoryWithBudget(ctx.getSessionId(), maxTokens);
            } else {
                history = sessionMemoryManager.getHistory(ctx.getSessionId());
            }
            for (SessionMemoryManager.ChatMessage msg : history) {
                if ("user".equals(msg.role())) {
                    messages.add(GatewayMessage.user(msg.content()));
                } else if ("assistant".equals(msg.role())) {
                    messages.add(GatewayMessage.assistant(msg.content()));
                }
            }
        }

        messages.add(GatewayMessage.user(ctx.getUserInput()));
        return messages;
    }

    private GatewayOptions buildOptions(SkillManifest primary, boolean hasFunctions) {
        GatewayOptions options = new GatewayOptions();
        options.setModel(primary.getModel());
        options.setTemperature(primary.getTemperature());
        options.setMaxTokens(primary.getMaxTokens());
        options.setStreamEnabled(primary.getStream() != null ? primary.getStream() : false);
        options.setEnableFunctionCalling(hasFunctions);
        options.setEnableToolExecution(true);
        return options;
    }
}
