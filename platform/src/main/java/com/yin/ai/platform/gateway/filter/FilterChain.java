package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;

/**
 * 阻塞式过滤器链。
 */
@FunctionalInterface
public interface FilterChain {
    GatewayResponse proceed(GatewayRequest request);
}
