package com.yin.ai.platform.observe.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * AI 平台统一 Tracing 服务 — 封装 OpenTelemetry Tracer，提供便捷的 Span 管理。
 * <p>
 * 支持 try-with-resources 模式自动关闭 Span：
 * <pre>{@code
 * try (SpanScope scope = tracing.startSpan("ai.router")) {
 *     scope.setAttribute("intent", "chat");
 *     // ... business logic
 *     scope.setOk();
 * }
 * }</pre>
 */
public class AiTracingService {

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public AiTracingService(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("wkc-ai-platform", "1.0.0");
    }

    /** 在当前上下文中创建一个新的子 Span，返回 AutoCloseable SpanScope。 */
    public SpanScope startSpan(String name) {
        return startSpan(name, SpanKind.INTERNAL);
    }

    /** 创建指定 SpanKind 的子 Span。 */
    public SpanScope startSpan(String name, SpanKind kind) {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanScope(span, scope);
    }

    /** 在给定的 parent Context 下创建子 Span。 */
    public SpanScope startSpan(String name, Context parent) {
        Span span = tracer.spanBuilder(name)
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanScope(span, scope);
    }

    /** 获取当前 OpenTelemetry Context，用于跨线程传播。 */
    public Context currentContext() {
        return Context.current();
    }

    /** 在指定 Context 下执行任务。 */
    public <T> T callWithContext(Context ctx, Callable<T> task) throws Exception {
        try (Scope ignored = ctx.makeCurrent()) {
            return task.call();
        }
    }

    /**
     * 创建带业务上下文的 Span — 自动注入 callerSkill、intent、userId、sessionId。
     */
    public SpanScope startSpanWithBusinessContext(String name, String callerSkill,
                                                    String intent, Long userId,
                                                    String sessionId) {
        SpanScope span = startSpan(name);
        if (callerSkill != null) span.setAttribute("callerSkill", callerSkill);
        if (intent != null) span.setAttribute("intent", intent);
        if (userId != null) span.setAttribute("userId", userId);
        if (sessionId != null) span.setAttribute("sessionId", sessionId);
        return span;
    }

    /** 将当前 trace context 注入到 HTTP 请求头中（跨服务传播）。 */
    public Map<String, String> injectTraceContext() {
        Map<String, String> headers = new HashMap<>();
        var openTelemetry = getOpenTelemetry();
        if (openTelemetry == null) return headers;

        TextMapSetter<Map<String, String>> setter = Map::put;
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), headers, setter);
        return headers;
    }

    /** 从 HTTP 请求头中提取上游 trace context（跨服务传播）。 */
    public Context extractTraceContext(Map<String, String> headers) {
        var openTelemetry = getOpenTelemetry();
        if (openTelemetry == null) return Context.current();

        TextMapGetter<Map<String, String>> getter = new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(Map<String, String> carrier) {
                return carrier.keySet();
            }

            @Override
            public String get(Map<String, String> carrier, String key) {
                return carrier != null ? carrier.get(key) : null;
            }
        };

        return openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), headers, getter);
    }

    private OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    /** SpanScope — 表示一个活跃的 Span，支持 AutoCloseable 和属性设置。 */
    public static class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public SpanScope setAttribute(String key, String value) {
            span.setAttribute(key, value);
            return this;
        }

        public SpanScope setAttribute(String key, long value) {
            span.setAttribute(key, value);
            return this;
        }

        public SpanScope setAttribute(String key, double value) {
            span.setAttribute(key, value);
            return this;
        }

        public SpanScope setAttribute(String key, boolean value) {
            span.setAttribute(key, value);
            return this;
        }

        /** 同时设置 traceId/model/caller 三个核心属性。 */
        public SpanScope setCoreAttributes(String traceId, String model, String caller) {
            if (traceId != null) span.setAttribute("traceId", traceId);
            if (model != null) span.setAttribute("model", model);
            if (caller != null) span.setAttribute("caller", caller);
            return this;
        }

        /** 标记 Span 成功。 */
        public void setOk() {
            span.setStatus(StatusCode.OK);
        }

        /** 记录异常到 Span。 */
        public void setError(Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage() != null ? e.getMessage() : "error");
            span.recordException(e);
        }

        public Span getSpan() { return span; }

        @Override
        public void close() {
            try {
                span.end();
            } finally {
                scope.close();
            }
        }
    }
}
