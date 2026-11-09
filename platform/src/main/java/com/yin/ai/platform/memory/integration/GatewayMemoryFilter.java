package com.yin.ai.platform.memory.integration;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.filter.FilterChain;
import com.yin.ai.platform.gateway.filter.GatewayFilter;
import com.yin.ai.platform.gateway.filter.StreamFilterChain;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Gateway Memory Filter — 将 AI Gateway 请求/响应自动记录到 Episodic Memory。
 * <p>
 * 每条请求记录为一条 MemoryCell，包含调用方、模型、延迟和 Token 用量信息。
 * Memory 记录失败不影响主流程（静默吞异常）。
 * <p>
 * Order 35（观测层），在 Metrics/Trace 之后执行。
 */
public class GatewayMemoryFilter implements GatewayFilter {

    private final MemoryRuntime memoryRuntime;
    private final boolean enabled;

    public GatewayMemoryFilter(MemoryRuntime memoryRuntime) {
        this(memoryRuntime, true);
    }

    public GatewayMemoryFilter(MemoryRuntime memoryRuntime, boolean enabled) {
        this.memoryRuntime = memoryRuntime;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public int order() {
        return 35;
    }

    @Override
    public boolean enabledByDefault() {
        return enabled;
    }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        if (!enabled) {
            return chain.proceed(request);
        }

        long start = System.currentTimeMillis();
        GatewayResponse response = chain.proceed(request);
        long elapsed = System.currentTimeMillis() - start;

        try {
            recordToMemory(request, response, elapsed);
        } catch (Exception ignored) {
            // Memory 记录失败不影响主流程
        }

        return response;
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        if (!enabled) {
            return chain.proceed(request);
        }

        long start = System.currentTimeMillis();
        return chain.proceed(request)
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - start;
                    try {
                        recordStreamToMemory(request, elapsed);
                    } catch (Exception ignored) {
                        // Memory 记录失败不影响主流程
                    }
                });
    }

    private void recordToMemory(GatewayRequest request, GatewayResponse response, long elapsedMs) {
        MemoryId id = createEpisodicId(request);
        String model = response != null ? response.getModel() : "unknown";
        int tokens = response != null && response.getTokenUsage() != null
                ? response.getTokenUsage().getTotalTokens() : 0;
        String content = String.format(
                "[GW] caller=%s model=%s elapsed=%dms tokens=%d",
                request.getCaller(), model, elapsedMs, tokens);
        memoryRuntime.append(id, content, memoryContext(request));
    }

    private void recordStreamToMemory(GatewayRequest request, long elapsedMs) {
        MemoryId id = createEpisodicId(request);
        String content = String.format(
                "[GW-STREAM] caller=%s elapsed=%dms",
                request.getCaller(), elapsedMs);
        memoryRuntime.append(id, content, memoryContext(request));
    }

    private MemoryId createEpisodicId(GatewayRequest request) {
        String ownerKey = "gateway";
        return MemoryId.of("gw-" + UUID.randomUUID().toString().substring(0, 8),
                MemoryType.EPISODIC, ownerKey);
    }

    private MemoryContext memoryContext(GatewayRequest request) {
        return new MemoryContext(
                request.getTraceId(),
                null,
                null,
                request.getCaller(),
                null,
                Instant.now(),
                java.util.Map.of());
    }
}
