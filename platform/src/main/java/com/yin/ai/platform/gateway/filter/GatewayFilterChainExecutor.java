package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Filter Chain 执行器 — 按 order 排序并构建调用链。
 */
@Component
public class GatewayFilterChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(GatewayFilterChainExecutor.class);

    private final List<GatewayFilter> filters;

    public GatewayFilterChainExecutor(List<GatewayFilter> filters) {
        this.filters = new ArrayList<>(filters);
        this.filters.sort(Comparator.comparingInt(GatewayFilter::order));
    }

    @PostConstruct
    void logRegisteredFilters() {
        if (filters.isEmpty()) {
            log.warn("[FILTER-CHAIN] 没有注册任何 GatewayFilter Bean！TerminalFilter 将自动补全");
        } else {
            log.info("[FILTER-CHAIN] 已注册 {} 个 Filter: {}", filters.size(),
                    filters.stream().map(f -> f.name() + "(" + f.order() + ")").toList());
        }
    }

    /**
     * 执行阻塞式 Filter Chain。
     */
    public GatewayResponse execute(GatewayRequest request) {
        FilterChain chain = buildChain(0);
        return chain.proceed(request);
    }

    /**
     * 执行流式 Filter Chain。
     */
    public Flux<StreamPacket> executeStream(GatewayRequest request) {
        StreamFilterChain chain = buildStreamChain(0);
        return chain.proceed(request);
    }

    private FilterChain buildChain(int index) {
        // 已到列表末尾但没有 TerminalFilter → 追加一个抛出明确错误的终端
        if (index >= filters.size()) {
            return request -> {
                throw new IllegalStateException(
                        "Filter chain exhausted without TerminalFilter — "
                        + "ensure a filter with order >= 100 (TerminalFilter) is registered. "
                        + "Registered filters: " + filters.stream()
                            .map(f -> f.name() + "(" + f.order() + ")").toList());
            };
        }

        GatewayFilter filter = filters.get(index);
        FilterChain next = buildChain(index + 1);
        return request -> filter.filter(request, next);
    }

    private StreamFilterChain buildStreamChain(int index) {
        if (index >= filters.size()) {
            return request -> Flux.error(new IllegalStateException(
                    "Stream filter chain exhausted without TerminalFilter — "
                    + "ensure a filter with order >= 100 (TerminalFilter) is registered."));
        }

        GatewayFilter filter = filters.get(index);
        StreamFilterChain next = buildStreamChain(index + 1);

        return request -> filter.filterStream(request, next);
    }
}
