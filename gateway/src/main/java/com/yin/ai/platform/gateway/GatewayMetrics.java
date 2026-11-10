package com.yin.ai.platform.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Gateway Micrometer 指标收集器。
 *
 * @deprecated 由 {@link com.yin.ai.platform.engine.metrics.AiMetrics} 替代，提供统一的五层指标。
 *             本类将在下一迭代移除。
 */
@Deprecated
@Component
public class GatewayMetrics {

    private final MeterRegistry meterRegistry;

    private Counter requestTotal;
    private Counter requestSuccess;
    private Counter requestFailure;
    private Counter retryCount;
    private Counter fallbackCount;

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        requestTotal = Counter.builder("ai.gateway.request.total")
                .description("Total AI Gateway requests").register(meterRegistry);
        requestSuccess = Counter.builder("ai.gateway.request.success")
                .description("Successful AI Gateway requests").register(meterRegistry);
        requestFailure = Counter.builder("ai.gateway.request.failure")
                .description("Failed AI Gateway requests").register(meterRegistry);
        retryCount = Counter.builder("ai.gateway.retry.count")
                .description("Retry count").register(meterRegistry);
        fallbackCount = Counter.builder("ai.gateway.fallback.count")
                .description("Fallback count").register(meterRegistry);
    }

    public void recordRequest() { requestTotal.increment(); }
    public void recordSuccess() { requestSuccess.increment(); }
    public void recordFailure() { requestFailure.increment(); }
    public void recordRetry() { retryCount.increment(); }
    public void recordFallback() { fallbackCount.increment(); }
}
