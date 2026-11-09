package com.yin.ai.platform.gateway;

import com.yin.ai.platform.structured.StructuredToolGenerator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把 {@code ai.gateway.thinking.enabled} 同步到 {@link StructuredToolGenerator} 的静态开关，
 * 使直接 ChatClient 路径（Agent 模式、Plan 生成、LLM 反思等）在思考模式下
 * 同样完全省略 tool_choice，从源头避免 "Thinking mode does not support this tool_choice" HTTP 400。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThinkingModeBootstrap {

    private final GatewayProperties gatewayProperties;

    @PostConstruct
    void init() {
        boolean enabled = gatewayProperties.getThinking() != null && gatewayProperties.getThinking().isEnabled();
        StructuredToolGenerator.setThinkingModeEnabled(enabled);
        String effort = gatewayProperties.getThinking() != null ? gatewayProperties.getThinking().getEffort() : null;
        log.info("[THINKING] thinking-mode={}, reasoning-effort={}", enabled, effort);
    }
}
