package com.yin.ai.platform.gateway.fallback;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;

/**
 * 降级策略 SPI — 按 caller 路由到不同的降级行为。
 */
public interface FallbackStrategy {

    /** 支持的调用方 */
    String supportedCaller();

    /** 执行降级 */
    GatewayResponse fallback(GatewayRequest request, Exception cause);
}
