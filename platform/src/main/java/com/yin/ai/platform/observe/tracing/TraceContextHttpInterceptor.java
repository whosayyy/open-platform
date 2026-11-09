package com.yin.ai.platform.observe.tracing;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * RestClient HTTP 请求拦截器 — 将当前 OpenTelemetry Context 中的
 * W3C Trace Context（traceparent / tracestate）注入到出站请求头，
 * 实现全链路追踪穿透到 LLM Provider。
 */
@Slf4j
public class TraceContextHttpInterceptor implements ClientHttpRequestInterceptor {

    private static final TextMapPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        Map<String, String> carrier = new HashMap<>();
        PROPAGATOR.inject(Context.current(), carrier, (map, key, value) -> map.put(key, value));

        if (!carrier.isEmpty()) {
            carrier.forEach((key, value) -> request.getHeaders().add(key, value));
            log.debug("[TRACE] injected trace context into {} {}: {}", request.getMethod(), request.getURI(), carrier);
        }

        return execution.execute(request, body);
    }
}
