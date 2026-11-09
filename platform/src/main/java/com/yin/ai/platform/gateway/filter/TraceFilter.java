package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Trace 过滤器（order=31）。
 * <p>
 * 从 ExecutionContext 获取 traceId 并注入到 MDC 和 GatewayRequest。
 */
@Slf4j
@Component
public class TraceFilter implements GatewayFilter {

    @Override
    public String name() { return "trace"; }

    @Override
    public int order() { return 31; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        // 从 ExecutionContext 获取 traceId
        ExecutionContext ctx = ExecutionContextHolder.getOptional().orElse(null);
        if (ctx != null) {
            request.setTraceId(ctx.traceId());
        }

        GatewayResponse response = chain.proceed(request);

        if (ctx != null && response != null) {
            response.setTraceId(ctx.traceId());
        }

        return response;
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        ExecutionContext ctx = ExecutionContextHolder.getOptional().orElse(null);
        if (ctx != null) {
            request.setTraceId(ctx.traceId());
        }
        return chain.proceed(request);
    }
}
