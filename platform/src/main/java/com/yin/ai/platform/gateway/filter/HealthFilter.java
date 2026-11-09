package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.health.ModelHealthManager;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 健康过滤器（order=32）— 记录模型调用的成功/失败，驱动熔断器状态转换。
 * <p>
 * 在 MetricsFilter 之前执行，确保失败计数在指标记录之前完成。
 */
@Slf4j
public class HealthFilter implements GatewayFilter {

    private final ModelHealthManager healthManager;

    public HealthFilter(ModelHealthManager healthManager) {
        this.healthManager = healthManager;
    }

    @Override
    public String name() { return "health"; }

    @Override
    public int order() { return 32; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        String modelName = resolveModelName(request);
        try {
            GatewayResponse response = chain.proceed(request);
            if (response.getModel() != null) {
                healthManager.recordSuccess(response.getModel());
            } else if (modelName != null) {
                healthManager.recordSuccess(modelName);
            }
            return response;
        } catch (Exception e) {
            if (modelName != null) {
                healthManager.recordFailure(modelName);
            }
            throw e;
        }
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return chain.proceed(request);
    }

    private String resolveModelName(GatewayRequest request) {
        if (request.getOptions() != null && request.getOptions().getModel() != null) {
            return request.getOptions().getModel();
        }
        return null; // will be determined by router
    }
}
