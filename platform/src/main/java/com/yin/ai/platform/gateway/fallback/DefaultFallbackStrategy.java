package com.yin.ai.platform.gateway.fallback;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认降级策略 — 返回友好提示文本。
 */
@Slf4j
@Component
public class DefaultFallbackStrategy implements FallbackStrategy {

    @Override
    public String supportedCaller() {
        return "default";
    }

    @Override
    public GatewayResponse fallback(GatewayRequest request, Exception cause) {
        log.warn("[GW-FB] caller={} traceId={} using default fallback",
                request.getCaller(), request.getTraceId());

        GatewayResponse response = new GatewayResponse("AI 服务暂时不可用，请稍后重试");
        response.setTraceId(request.getTraceId());
        response.setFallback(true);
        response.setModel(request.getOptions() != null ? request.getOptions().getModel() : null);
        return response;
    }
}
