package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.engine.stream.StreamPacket;
import reactor.core.publisher.Flux;

/**
 * AI Gateway 过滤器 SPI。
 * <p>
 * 所有横切关注点（Retry、Fallback、Metrics、Trace、Audit）通过此接口织入。
 */
public interface GatewayFilter {

    /** Filter 名称 */
    String name();

    /**
     * Filter 执行顺序（越小越先执行）。
     * 建议范围：
     *   10-19: 预处理
     *   20-29: 容错 (Retry, Fallback)
     *   30-39: 观测 (Metrics, Trace)
     *   40-49: 后处理 (Audit)
     */
    int order();

    /** 是否默认启用 */
    default boolean enabledByDefault() { return true; }

    /** 阻塞式调用拦截 */
    GatewayResponse filter(GatewayRequest request, FilterChain chain);

    /** 流式调用拦截 */
    Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain);
}
