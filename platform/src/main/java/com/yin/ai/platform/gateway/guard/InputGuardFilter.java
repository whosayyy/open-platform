package com.yin.ai.platform.gateway.guard;

import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.filter.FilterChain;
import com.yin.ai.platform.gateway.filter.GatewayFilter;
import com.yin.ai.platform.gateway.filter.StreamFilterChain;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Input Guard 过滤器（order=5）— Gateway Filter Chain 第一道防线。
 * <p>
 * 在 LLM 调用之前检测并拦截 Prompt Injection 攻击，包括：
 * DAN 越狱、角色覆盖、分隔符注入、上下文劫持、重复绕过、指令越权、编码绕过。
 * <p>
 * 支持可插拔的 PII 检测器（{@link PIIDetector} SPI），先做注入检测再做 PII 检测。
 */
@Slf4j
@Component
public class InputGuardFilter implements GatewayFilter {

    private static final String BLOCKED_REASON_KEY = "blockedReason";

    private final InputGuardProperties properties;
    private final DefaultInputGuardRules rulesProvider;
    private final List<PIIDetector> piiDetectors;

    public InputGuardFilter(InputGuardProperties properties,
                            DefaultInputGuardRules rulesProvider,
                            List<PIIDetector> piiDetectors) {
        this.properties = properties;
        this.rulesProvider = rulesProvider;
        this.piiDetectors = piiDetectors;
    }

    @Override
    public String name() {
        return "input-guard";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public boolean enabledByDefault() {
        return properties.isEnabled();
    }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.proceed(request);
        }

        String userInput = extractUserInput(request);

        // 1. Prompt Injection 检测
        if (userInput != null && !userInput.isBlank()) {
            InputGuardRule hit = checkPromptInjection(userInput);
            if (hit != null) {
                log.warn("[GUARD] traceId={} rule={} severity={} inputPreview=\"{}\"",
                        request.getTraceId(), hit.getName(), hit.getSeverity(),
                        truncate(userInput, 100));
                return buildBlockedResponse(request, hit.getMessage(), hit.getName());
            }

            // 2. PII 检测
            PIIDetector.PIIResult piiResult = checkPII(userInput);
            if (piiResult != null && piiResult.blocked()) {
                log.warn("[GUARD] traceId={} piiBlocked types={}",
                        request.getTraceId(), piiResult.matchedTypes());
                return buildBlockedResponse(request, "输入包含敏感个人信息，请修改后重试",
                        "pii-detected:" + String.join(",", piiResult.matchedTypes()));
            }
        }

        return chain.proceed(request);
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.proceed(request);
        }

        String userInput = extractUserInput(request);
        if (userInput != null && !userInput.isBlank()) {
            InputGuardRule hit = checkPromptInjection(userInput);
            if (hit != null) {
                log.warn("[GUARD] traceId={} stream blocked rule={}",
                        request.getTraceId(), hit.getName());
                return Flux.just(StreamPacket.builder()
                        .type("text")
                        .content("[BLOCKED] " + hit.getMessage())
                        .build());
            }
        }

        return chain.proceed(request);
    }

    // ── private helpers ──

    private InputGuardRule checkPromptInjection(String input) {
        for (InputGuardRule rule : rulesProvider.getEffectiveRules()) {
            if (!rule.isEnabled()) continue;
            try {
                if (rule.matches(input)) {
                    return rule;
                }
            } catch (Exception e) {
                log.error("[GUARD] rule={} evaluation error: {}", rule.getName(), e.getMessage());
            }
        }
        return null;
    }

    private PIIDetector.PIIResult checkPII(String input) {
        for (PIIDetector detector : piiDetectors) {
            try {
                PIIDetector.PIIResult result = detector.detect(input);
                if (result != null && result.blocked()) {
                    return result;
                }
            } catch (Exception e) {
                log.error("[GUARD] PII detector error: {}", e.getMessage());
            }
        }
        return null;
    }

    private GatewayResponse buildBlockedResponse(GatewayRequest request, String message, String ruleName) {
        GatewayResponse response = new GatewayResponse(message);
        response.setTraceId(request.getTraceId());
        response.setMetadata(Map.of(BLOCKED_REASON_KEY, ruleName));
        return response;
    }

    /** 提取用户最新输入（最后一条 user 角色的消息）。 */
    private String extractUserInput(GatewayRequest request) {
        List<GatewayMessage> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return request.getSystemPrompt();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == GatewayMessage.Role.user) {
                return messages.get(i).getContent();
            }
        }
        return request.getSystemPrompt();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
