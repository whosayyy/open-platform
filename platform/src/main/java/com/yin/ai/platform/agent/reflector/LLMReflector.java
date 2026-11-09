package com.yin.ai.platform.agent.reflector;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.agent.observer.Observation.ObservationStatus;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LLM 驱动反思器 — 通过 LLM 评估任务进度并决定下一步行动。
 * <p>
 * 启发式降级:
 * <ul>
 *   <li>步骤失败 + 重试次数超限 → COMPLETE</li>
 *   <li>成功 + 已达 maxSteps → COMPLETE</li>
 *   <li>LLM 调用失败 → CONTINUE（保守策略）</li>
 * </ul>
 * <p>
 * Prompt 策略可通过 {@link ReflectorPromptStrategy} 注入。
 */
public class LLMReflector implements Reflector {

    private static final Logger log = LoggerFactory.getLogger(LLMReflector.class);

    private final UnifiedChatClient chatClient;
    private final ReflectorPromptStrategy promptStrategy;

    public LLMReflector(UnifiedChatClient chatClient) {
        this(chatClient, ReflectorPromptStrategy.defaults());
    }

    public LLMReflector(UnifiedChatClient chatClient, ReflectorPromptStrategy promptStrategy) {
        this.chatClient = chatClient;
        this.promptStrategy = promptStrategy != null ? promptStrategy : ReflectorPromptStrategy.defaults();
    }

    @Override
    public Decision reflect(Observation observation, AgentContext ctx) {
        if (observation.status() == ObservationStatus.FAILURE && ctx.hasExceededRetries()) {
            log.warn("[REFLECTOR] step failed + retries exhausted, completing");
            return Decision.COMPLETE;
        }

        if (observation.status() == ObservationStatus.SUCCESS
                && ctx.getCurrentStep() >= ctx.getConfig().maxSteps()) {
            log.info("[REFLECTOR] success + maxSteps reached, completing");
            return Decision.COMPLETE;
        }

        try {
            String prompt = promptStrategy.buildReflectPrompt(observation, ctx);

            GatewayRequest request = new GatewayRequest();
            request.setMessages(List.of(GatewayMessage.user(prompt)));
            request.setCaller("agent.reflector");

            GatewayOptions options = new GatewayOptions();
            options.setTemperature(0.1);
            options.setMaxTokens(10);
            options.setEnableFunctionCalling(false);
            request.setOptions(options);

            GatewayResponse response;
            ExecutionContext execCtx = ctx.getExecCtx();
            if (execCtx != null) {
                response = ExecutionContextHolder.runWith(execCtx, () -> chatClient.chat(request));
            } else {
                response = chatClient.chat(request);
            }
            Decision decision = parseDecision(response.getContent());

            log.info("[REFLECTOR] observation={} decision={}", observation.status(), decision);
            return decision;
        } catch (Exception e) {
            log.warn("[REFLECTOR] LLM call failed, defaulting to CONTINUE: {}", e.getMessage());
            return Decision.CONTINUE;
        }
    }

    static Decision parseDecision(String content) {
        if (content == null || content.isBlank()) {
            return Decision.CONTINUE;
        }
        String upper = content.trim().toUpperCase();
        if (upper.contains("COMPLETE")) {
            return Decision.COMPLETE;
        }
        if (upper.contains("RETRY")) {
            return Decision.RETRY;
        }
        return Decision.CONTINUE;
    }
}
