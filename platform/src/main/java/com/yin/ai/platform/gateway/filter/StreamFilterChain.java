package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.engine.stream.StreamPacket;
import reactor.core.publisher.Flux;

/**
 * 流式过滤器链。
 */
@FunctionalInterface
public interface StreamFilterChain {
    Flux<StreamPacket> proceed(GatewayRequest request);
}
