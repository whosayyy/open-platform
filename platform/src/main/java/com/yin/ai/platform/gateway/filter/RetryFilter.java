package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.engine.ExecutionEngine;
import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import com.yin.ai.platform.core.runtime.execution.ExecutionResult;
import com.yin.ai.platform.core.runtime.execution.RetryPolicy;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.engine.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * 重试过滤器（order=21）。
 * <p>
 * 可重试异常时退避重试。重试耗尽后抛出异常，
 * 由外层 FallbackFilter 处理降级。
 */
@Slf4j
@Component
public class RetryFilter implements GatewayFilter {

    private static final int MAX_RETRIES = 2;

    private final ExecutionEngine engine;

    public RetryFilter(ExecutionEngine engine) {
        this.engine = engine;
    }

    @Override
    public String name() { return "retry"; }

    @Override
    public int order() { return 22; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        // 从上下文获取 ExecutionContext
        ExecutionContext ctx = ExecutionContextHolder.get();

        // 执行 LLM 调用
        ExecutionResult<GatewayResponse> result = engine.executeWithRetry(
                ctx,
                () -> chain.proceed(request),
                ExecutionOptions.of("gateway-chat", Duration.ofSeconds(120)),
                RetryPolicy.standard()
        );

        if (result.success()) {
            return result.data();
        }

        // 重试耗尽：抛出异常，交由外层 FallbackFilter 处理
        throw new RetryExhaustedException(result.error(), result.retryCount());
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        // 流式场景暂不实现重试
        return chain.proceed(request);
    }
}
