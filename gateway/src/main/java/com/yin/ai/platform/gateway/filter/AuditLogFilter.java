package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.audit.AuditLogEntry;
import com.yin.ai.platform.gateway.audit.AuditLogStore;
import com.yin.ai.platform.engine.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志过滤器（order=40）。
 * <p>
 * 记录每次 LLM 调用的输入/输出/模型/Token，委托给 {@link AuditLogStore} SPI 存储。
 * 默认使用 {@link com.yin.ai.platform.gateway.audit.NoopAuditLogStore}（仅日志输出），
 * 业务层可通过注册自定义 {@link AuditLogStore} Bean 覆盖。
 */
@Slf4j
@Component
public class AuditLogFilter implements GatewayFilter {

    private final GatewayProperties properties;
    private final AuditLogStore auditLogStore;

    public AuditLogFilter(GatewayProperties properties, AuditLogStore auditLogStore) {
        this.properties = properties;
        this.auditLogStore = auditLogStore;
    }

    @Override
    public String name() { return "audit-log"; }

    @Override
    public int order() { return 40; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        if (!properties.isAuditLogEnabled()) {
            return chain.proceed(request);
        }

        try {
            GatewayResponse response = chain.proceed(request);
            if (response != null) {
                auditLogStore.save(buildEntry(request, response, null));
            }
            return response;
        } catch (Exception e) {
            auditLogStore.save(buildEntry(request, null, e));
            throw e;
        }
    }

    private AuditLogEntry buildEntry(GatewayRequest request, GatewayResponse response, Exception error) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setTraceId(request.getTraceId());
        entry.setCaller(request.getCaller());
        entry.setCreatedAt(LocalDateTime.now());
        entry.setTenantId(request.getTenantId());
        entry.setUserId(request.getUserId());
        entry.setSkill(request.getCaller());
        entry.setPromptVersion(request.getPromptVersion());
        entry.setExperimentId(request.getExperimentId());
        entry.setVariantId(request.getVariantId());

        // 从请求中提取的通用字段（无论成功/失败都设置）
        String lastUserMsg = extractLastUserMessage(request);
        if (lastUserMsg != null) {
            entry.setUserMessageHash(hashForAudit(lastUserMsg));
        }
        entry.setHasFunctionCalling(request.getOptions() != null
                && request.getOptions().isEnableFunctionCalling());

        if (response != null) {
            entry.setModel(response.getModel());
            entry.setLatencyMs(response.getLatencyMs());
            entry.setTtftMs(response.getTtftMs());
            entry.setRetryCount(response.getRetryCount());

            if (response.getTokenUsage() != null) {
                entry.setPromptTokens(response.getTokenUsage().getPromptTokens());
                entry.setCompletionTokens(response.getTokenUsage().getCompletionTokens());
                entry.setTotalTokens(response.getTokenUsage().getTotalTokens());
            }

            if (response.getContent() != null) {
                entry.setResponsePreview(safeTruncate(response.getContent(), 500));
            }

            // 记录真实工具调用名（从响应中的 toolCalls 提取，逗号分隔）
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                List<String> names = response.getToolCalls().stream()
                        .map(tc -> tc != null ? tc.getName() : null)
                        .filter(n -> n != null && !n.isBlank())
                        .distinct()
                        .toList();
                if (!names.isEmpty()) {
                    entry.setToolNames(String.join(",", names));
                }
            }

            entry.setStatus(response.isFallback() ? "FALLBACK" : "SUCCESS");
        } else {
            entry.setStatus("ERROR");
            if (error != null) {
                entry.setResponsePreview(safeTruncate(error.getMessage(), 500));
            }
        }

        return entry;
    }

    private String extractLastUserMessage(GatewayRequest request) {
        List<GatewayMessage> messages = request.getMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == GatewayMessage.Role.user) {
                return messages.get(i).getContent();
            }
        }
        return null;
    }

    private static String safeTruncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    private static String hashForAudit(String text) {
        if (text == null) return null;
        int hash = text.hashCode();
        return Integer.toHexString(hash);
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return chain.proceed(request);
    }
}
