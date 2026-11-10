package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.engine.metrics.AiMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 指标过滤器（order=30）。
 * <p>
 * 记录 Gateway 层请求总数、延迟分布、成功/失败/降级/重试计数。
 * 通过 {@link AiMetrics} 统一输出到 Micrometer。
 */
@Slf4j
@Component
public class MetricsFilter implements GatewayFilter {

    private final AiMetrics metrics;

    public MetricsFilter(AiMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public String name() { return "metrics"; }

    @Override
    public int order() { return 30; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        long start = System.currentTimeMillis();

        try {
            GatewayResponse response = chain.proceed(request);
            long elapsed = System.currentTimeMillis() - start;
            response.setLatencyMs(elapsed);

            String status = response.isFallback() ? "fallback" : "success";
            String model = response.getModel() != null ? response.getModel() : "unknown";
            String caller = request.getCaller() != null ? request.getCaller() : "unknown";

            metrics.recordGatewayRequest(caller, model, status, elapsed);

            if (response.isFallback()) {
                metrics.recordGatewayFallback();
            }
            return response;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String caller = request.getCaller() != null ? request.getCaller() : "unknown";
            metrics.recordGatewayRequest(caller, "unknown", "error", elapsed);
            metrics.recordGatewayError();
            throw e;
        }
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return chain.proceed(request);
    }
}
