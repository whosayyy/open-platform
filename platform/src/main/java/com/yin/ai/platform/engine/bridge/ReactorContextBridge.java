package com.yin.ai.platform.engine.bridge;

import com.yin.ai.platform.engine.ExecutionContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 平台线程世界 ↔ Reactor 响应式世界的上下文传播桥。
 */
@Component
public class ReactorContextBridge {

    /**
     * 将 ExecutionContext 注入到 Reactor Context。
     */
    public static <T> Mono<T> wrapWithContext(ExecutionContext ctx, Mono<T> mono) {
        return mono.contextWrite(context -> {
            var c = context.put("traceId", ctx.traceId());
            return c.put("executionContext", ctx);
        });
    }

    /**
     * 从 Reactor Context 恢复 ExecutionContext。
     */
    public static ExecutionContext fromReactorContext() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey("executionContext")) {
                return Mono.just(ctx.<ExecutionContext>get("executionContext"));
            }
            return Mono.error(new IllegalStateException("No ExecutionContext in Reactor context"));
        }).block();
    }
}
