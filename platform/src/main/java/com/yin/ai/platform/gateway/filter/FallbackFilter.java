package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.fallback.FallbackStrategy;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 降级过滤器（order=20，最外层）。
 * <p>
 * 当内层 Filter 抛出异常（RetryFilter 重试耗尽后抛出）时，
 * 按 caller 路由到对应的 FallbackStrategy。
 */
@Slf4j
@Component
public class FallbackFilter implements GatewayFilter {

    private final Map<String, FallbackStrategy> strategyMap;

    public FallbackFilter(List<FallbackStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(FallbackStrategy::supportedCaller, s -> s));
    }

    @Override
    public String name() { return "fallback"; }

    @Override
    public int order() { return 20; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        try {
            return chain.proceed(request);
        } catch (RetryExhaustedException e) {
            log.warn("[GW-FB] traceId={} caller={} retries exhausted, falling back",
                    request.getTraceId(), request.getCaller());
            return executeFallback(request, e);
        } catch (RuntimeException e) {
            // HTTP 429/503 等可恢复错误 → 降级
            if (e.getMessage() != null
                    && (e.getMessage().contains("429") || e.getMessage().contains("503"))) {
                log.warn("[GW-FB] traceId={} caller={} rate_limit={}",
                        request.getTraceId(), request.getCaller(), e.getMessage());
                return executeFallback(request, e);
            }
            // 编程错误（NPE/IllegalArgument）→ 向上传播，不被吞没
            throw e;
        }
    }

    private GatewayResponse executeFallback(GatewayRequest request, Exception cause) {
        FallbackStrategy strategy = strategyMap.get(request.getCaller());
        if (strategy != null) {
            return strategy.fallback(request, cause);
        }
        return defaultFallback(request);
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return chain.proceed(request);
    }

    private GatewayResponse defaultFallback(GatewayRequest request) {
        GatewayResponse response = new GatewayResponse("AI 服务暂时不可用，请稍后重试");
        response.setTraceId(request.getTraceId());
        response.setFallback(true);
        return response;
    }
}
