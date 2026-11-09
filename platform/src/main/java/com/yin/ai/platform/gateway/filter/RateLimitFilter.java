package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.config.RateLimitConfig;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * API 限流过滤器（order=15）— 基于 Redis 原子计数器按 userId + API 路径限流。
 * <p>
 * 在请求进入 Retry/Fallback 等容错层之前拦截，避免资源浪费。
 * Redis 不可用时降级为放行（fail-open）。
 */
@Slf4j
@Component
public class RateLimitFilter implements GatewayFilter {

    private static final String RATE_LIMITED_KEY = "rateLimited";

    private final RedissonClient redisson;
    private final RateLimitConfig config;

    public RateLimitFilter(ObjectProvider<RedissonClient> redissonProvider,
                          RateLimitConfig config) {
        this.redisson = redissonProvider.getIfAvailable();
        this.config = config;
    }

    @Override
    public String name() {
        return "rate-limit";
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public boolean enabledByDefault() {
        return config.isEnabled();
    }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        if (!config.isEnabled() || redisson == null) {
            return chain.proceed(request);
        }

        String key = buildKey(request);
        try {
            RAtomicLong counter = redisson.getAtomicLong(key);
            long current = counter.incrementAndGet();
            if (current == 1) {
                counter.expire(config.getWindowSeconds(), TimeUnit.SECONDS);
            }
            if (current > config.getMaxRequestsPerMinute()) {
                log.warn("[RATE-LIMIT] rate limit exceeded: key={}, current={}", key, current);
                GatewayResponse response = new GatewayResponse("请求过于频繁，请稍后重试 (rate limited)");
                response.setTraceId(request.getTraceId());
                response.setMetadata(Map.of(RATE_LIMITED_KEY, true));
                return response;
            }
        } catch (Exception e) {
            log.debug("[RATE-LIMIT] Redis unavailable, failing open: {}", e.getMessage());
        }

        return chain.proceed(request);
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        if (!config.isEnabled() || redisson == null) {
            return chain.proceed(request);
        }

        String key = buildKey(request);
        try {
            RAtomicLong counter = redisson.getAtomicLong(key);
            long current = counter.incrementAndGet();
            if (current == 1) {
                counter.expire(config.getWindowSeconds(), TimeUnit.SECONDS);
            }
            if (current > config.getMaxRequestsPerMinute()) {
                log.warn("[RATE-LIMIT] rate limit exceeded (stream): key={}, current={}", key, current);
                return Flux.error(new RateLimitExceededException(key));
            }
        } catch (Exception e) {
            log.debug("[RATE-LIMIT] Redis unavailable, failing open: {}", e.getMessage());
        }

        return chain.proceed(request);
    }

    private String buildKey(GatewayRequest request) {
        String userId = request.getTenantId() != null ? request.getTenantId() : "anonymous";
        String caller = request.getCaller() != null ? request.getCaller() : "default";
        return config.getKeyPrefix() + userId + ":" + caller;
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String key) {
            super("Rate limit exceeded: " + key);
        }
    }
}
