package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.gateway.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.core.agent.pattern.StepType;

/**
 * SIMPLE 模式 — 单次 LLM 调用，无工具、无多步。
 * <p>
 * 适用场景：简单问答、闲聊、翻译短句等。
 * 特点：零记忆开销（不创建 WorkingMemory），不做 Consolidation。
 */
public class SimpleAgentPattern implements AgentPattern {

    private static final Logger log = LoggerFactory.getLogger(SimpleAgentPattern.class);

    private final GatewayTextClient chatClient;

    public SimpleAgentPattern(GatewayTextClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public AgentMode mode() {
        return AgentMode.DIRECT;
    }

    @Override
    public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
        return Flux.create(sink -> {
            String taskId = ctx.getTaskId();
            EventPublisher delegate = ctx.getEventPublisher();
            EventPublisher publisher = new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                    delegate.publish(event);
                    sink.next(event);
                }

                @Override
                public reactor.core.publisher.Flux<AgentEvent> replay(String taskId, String lastEventId) {
                    return delegate.replay(taskId, lastEventId);
                }
            };
            ctx.setEventPublisher(publisher);

            try {
                if (ctx.getState() == com.yin.ai.platform.agent.task.TaskState.CREATED) {
                    ctx.transitionTo(com.yin.ai.platform.agent.task.TaskState.RUNNING);
                }
                // 发射开始事件
                publisher.publish(AgentEvent.stepStarted(taskId, "simple-chat", "LLM 对话"));
                var reasoning = ReasoningOptionsSupport.resolve(ctx, StepType.CONTENT_GENERATION);
                publisher.publish(AgentEvent.reasoningStarted(taskId, "simple-chat", reasoning));

                // 单次 LLM 调用
                LlmCall response = callWithConfig(ctx, ctx.getGoal());
                ctx.accumulateTokenUsage(response.usage());
                int usedTokens = response.usage() != null && response.usage().getTotalTokens() != null
                        ? response.usage().getTotalTokens() : 0;
                publisher.publish(AgentEvent.reasoningCompleted(
                        taskId, "simple-chat", reasoning, usedTokens));

                // 保存结果
                ctx.putVariable("response", response.content());
                ctx.putVariable("finalOutput", response.content());

                // 发射完成事件
                publisher.publish(AgentEvent.stepCompleted(taskId, "simple-chat", response.content()));
                sink.complete();

            } catch (Exception e) {
                log.error("[SIMPLE] execution failed taskId={}", taskId, e);
                publisher.publish(AgentEvent.stepFailed(taskId, "simple-chat", e.getMessage()));
                sink.error(e);
            }
        });
    }

    private LlmCall callWithConfig(AgentExecutionContext ctx, String userPrompt) {
        Object sessionContext = ctx.getVariable("sessionContext");
        if (sessionContext != null && !sessionContext.toString().isBlank()) {
            userPrompt = sessionContext + "\n\n" + userPrompt;
        }
        String sp = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
        Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
        var executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof com.yin.ai.platform.gateway.ModelExecutionOptions value ? value : null;
        GatewayResponse response = chatClient.generate(
                "agent:simple", userPrompt, sp, temp, executionOptions);
        String content = response != null && response.getContent() != null ? response.getContent() : "";
        TokenUsage tokenUsage = response != null && response.getTokenUsage() != null
                ? response.getTokenUsage() : new TokenUsage();
        return new LlmCall(content != null ? content : "", tokenUsage);
    }

    private record LlmCall(String content, TokenUsage usage) {}

    @Override
    public boolean resumable() {
        return false;
    }

    @Override
    public int priority() {
        return 100;
    }
}
