package com.yin.ai.platform.observe.tracing;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 【P0-1 修复】入站 traceparent 提取 Filter — 跨服务全链路追踪的关键接线。
 * <p>
 * <b>问题</b>：AiTracingService.extractTraceContext 早已实现但从无调用方——
 * HTTP 入口不提取上游 traceparent，导致每个服务都成为独立 root trace，
 * 跨服务无法按 traceId 串联追因。
 * <p>
 * <b>修复</b>：本 Filter 在请求进入时：
 * <ol>
 *   <li>从请求头提取 W3C traceparent（含 traceparent / tracestate）</li>
 *   <li>提取成功 → 将上游 Context 设为当前 Context（makeCurrent）</li>
 *   <li>请求处理期间所有 span（AiTracingAspect / AiTracingService）自动成为其子 span</li>
 *   <li>请求结束 → 关闭 Scope 恢复原 Context</li>
 * </ol>
 * 与出站 {@code TraceContextHttpInterceptor}（注入 traceparent）配合，闭环跨服务传播。
 * <p>
 * 注意：AiTracingService 仅在 ai.observability.tracing.enabled=true 时注册，
 * 用 ObjectProvider 降级注入，tracing 关闭时本 Filter 静默跳过，不影响启动。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceContextFilter extends OncePerRequestFilter {

    private final ObjectProvider<AiTracingService> tracingServiceProvider;

    public TraceContextFilter(ObjectProvider<AiTracingService> tracingServiceProvider) {
        this.tracingServiceProvider = tracingServiceProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AiTracingService tracingService = tracingServiceProvider.getIfAvailable();
        if (tracingService == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 提取上游 traceparent
        Map<String, String> headers = extractHeaders(request);
        Context upstream = tracingService.extractTraceContext(headers);

        boolean extracted = headers.get("traceparent") != null;
        if (!extracted) {
            filterChain.doFilter(request, response);
            return;
        }

        // 使上游 Context 成为当前线程 Context — 后续 span 自动成为其子节点
        try (Scope ignored = upstream.makeCurrent()) {
            filterChain.doFilter(request, response);
        }
    }

    /** 提取请求头（W3C 传播器只关心 traceparent/tracestate，但全量提取兜底）。 */
    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }
}
