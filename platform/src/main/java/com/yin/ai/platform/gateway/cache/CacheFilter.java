package com.yin.ai.platform.gateway.cache;

import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.filter.FilterChain;
import com.yin.ai.platform.gateway.filter.GatewayFilter;
import com.yin.ai.platform.gateway.filter.StreamFilterChain;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 语义缓存过滤器 (order=8) — 在 InputGuard 之前拦截请求。
 * <p>
 * 命中缓存时直接返回，跳过 LLM 调用。未命中时放行，并在 LLM 响应后异步回填缓存。
 * 异步操作通过 ExecutionContext 传播 traceId，走虚拟线程执行，不占用 commonPool。
 * <p>
 * <b>内部调用排除</b>：caller=agent.executor（Agent 步骤执行）和 caller=agent.reflector
 * （LLM 反思决策）跳过缓存搜索与回填，避免步骤描述或"CONTINUE"/"COMPLETE"等短文本
 * 污染缓存池导致不同用户查询误命中。
 */
@Slf4j
public class CacheFilter implements GatewayFilter {

    /** 内部调用方 —— 这些 caller 的请求不参与语义缓存，避免污染缓存池 */
    private static final String CALLER_AGENT_EXECUTOR = "agent.executor";
    private static final String CALLER_AGENT_REFLECTOR = "agent.reflector";
    private static final String CALLER_QUERY_REWRITER = "query-rewriter";
    private static final String CALLER_AGENT_PLANNER = "agent.planner";

    private final SemanticCacheManager cacheManager;

    public CacheFilter(SemanticCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public int order() {
        return 8;
    }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        if (cacheManager == null || isInternalCaller(request)) {
            return chain.proceed(request);
        }
        String userInput = extractLastUserMessage(request);
        if (userInput == null) {
            return chain.proceed(request);
        }

        String cached = cacheManager.search(userInput);
        if (cached != null) {
            log.info("[CACHE-FILTER] cache hit, returning cached response");
            GatewayResponse response = new GatewayResponse(cached);
            response.setTraceId(request.getTraceId());
            response.setCached(true);
            return response;
        }

        GatewayResponse response = chain.proceed(request);

        // 异步回填缓存：使用虚拟线程避免阻塞，异常独立处理不污染主流程
        // 注意：降级响应（fallback=true）不缓存，避免将错误信息写入缓存
        if (response.getContent() != null && !response.isFallback()) {
            String model = response.getModel() != null ? response.getModel() : "unknown";
            ExecutionContext ctx = ExecutionContextHolder.getOptional().orElse(null);
            Thread.ofVirtual().name("cache-store-")
                    .start(() -> asyncectxtCacheStore(ctx, userInput, response.getContent(), model));
        }

        return response;
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        if (cacheManager == null || isInternalCaller(request)) {
            return chain.proceed(request);
        }
        String userInput = extractLastUserMessage(request);
        if (userInput == null) {
            return chain.proceed(request);
        }

        String cached = cacheManager.search(userInput);
        if (cached != null) {
            log.info("[CACHE-FILTER] cache hit in stream, returning cached response");
            StreamPacket packet = new StreamPacket();
            packet.setType("text");
            packet.setContent(cached);
            packet.setTaskId(request.getTraceId());
            packet.setSeq(0);
            packet.setTs(System.currentTimeMillis());
            packet.setTraceId(request.getTraceId());
            return Flux.just(packet);
        }

        StringBuffer fullResponse = new StringBuffer();
        return chain.proceed(request)
                .doOnNext(packet -> {
                    if ("text".equals(packet.getType())
                            && packet.getContent() instanceof String text) {
                        fullResponse.append(text);
                    }
                })
                .doOnComplete(() -> {
                    if (fullResponse.length() > 0) {
                        String model = request.getOptions() != null
                                ? request.getOptions().getModel() : "unknown";
                        String responseText = fullResponse.toString();
                        ExecutionContext ctx = ExecutionContextHolder.getOptional().orElse(null);
                        // 流式路径：不阻塞 Flux 完成信号，独立异步写入
                        Mono.fromRunnable(() ->
                                asyncectxtCacheStore(ctx, userInput, responseText, model))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                        v -> {},
                                        err -> log.warn("[CACHE-FILTER] stream cache store failed: {}",
                                                err.getMessage())
                                );
                    }
                });
    }

    /**
     * 判断是否为内部 Agent 调用（不参与语义缓存）。
     * <p>
     * Agent 步骤执行（executor）和反思决策（reflector）的"用户消息"实际上是
     * 步骤描述或反思 prompt，与真正的用户自然语言查询语义不同。缓存这些条目
     * 会导致不同用户查询因 embedding 相似而误命中。
     */
    private boolean isInternalCaller(GatewayRequest request) {
        String caller = request.getCaller();
        return CALLER_AGENT_EXECUTOR.equals(caller)
                || CALLER_AGENT_REFLECTOR.equals(caller)
                || CALLER_QUERY_REWRITER.equals(caller)
                || CALLER_AGENT_PLANNER.equals(caller);
    }

    /**
     * 异步缓存存储（虚拟线程执行），异常自包含不污染调用方。
     */
    private void asyncectxtCacheStore(ExecutionContext ctx, String userInput,
                                   String content, String model) {
        if (ctx != null) {
            ExecutionContextHolder.runWith(ctx, () -> {
                cacheManager.store(userInput, content, model);
            });
        } else {
            cacheManager.store(userInput, content, model);
        }
    }

    private String extractLastUserMessage(GatewayRequest request) {
        List<GatewayMessage> messages = request.getMessages();
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == GatewayMessage.Role.user) {
                return messages.get(i).getContent();
            }
        }
        return null;
    }
}
