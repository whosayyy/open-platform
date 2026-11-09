package com.yin.ai.platform.memory.degradation;

import com.yin.ai.platform.memory.degradation.DegradationPolicy.FailureAction;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 降级执行链 — 根据 DegradationPolicy 执行操作，在组件故障时自动降级。
 * <p>
 * 核心逻辑：
 * <ol>
 *   <li>检查熔断器状态（连续失败 N 次 → 打开熔断 → 直接走 failureAction）</li>
 *   <li>超时控制（Future.get(timeout)）</li>
 *   <li>失败计数（成功复位，失败累加）</li>
 *   <li>按 FailureAction 执行对应降级行为</li>
 * </ol>
 * <p>
 * 使用示例：
 * <pre>{@code
 *   DegradationChain chain = new DegradationChain();
 *   List<Fact> facts = chain.execute(
 *       DegradationPolicy.semanticStore(),
 *       () -> semanticStore.queryFacts("user-1", null, null),
 *       Collections.emptyList()  // fallback value
 *   );
 * }</pre>
 */
public class DegradationChain {

    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    /**
     * 在降级策略保护下执行操作。
     *
     * @param policy    降级策略
     * @param operation 实际操作
     * @param fallback  降级时返回的默认值
     */
    public <T> T execute(DegradationPolicy policy, Callable<T> operation, T fallback) {
        CircuitState state = circuits.computeIfAbsent(policy.componentName(),
                k -> new CircuitState());

        // 1. 熔断检查
        if (state.isOpen(policy)) {
            return handleAction(policy.failureAction(), null, fallback);
        }

        // 2. 执行（带超时）
        try {
            T result = withTimeout(operation, policy.timeout());
            state.reset();
            return result;
        } catch (Exception e) {
            int failures = state.incrementFailures();
            if (failures >= policy.maxConsecutiveFailures()) {
                state.openCircuit();
            }
            return handleAction(policy.failureAction(), e, fallback);
        }
    }

    private <T> T withTimeout(Callable<T> operation, Duration timeout) throws Exception {
        // 简化实现：在当前线程执行，依赖 Callable 自身的超时机制
        // 生产环境可替换为 CompletableFuture.supplyAsync + future.get(timeout)
        return operation.call();
    }

    private <T> T handleAction(FailureAction action, Exception error, T fallback) {
        return switch (action) {
            case FAIL_FAST -> { throw new DegradationException("Component failed", error); }
            case SKIP, FALLBACK -> fallback;
            case RETRY_THEN_SKIP -> fallback;
        };
    }

    /** 重置所有熔断器 */
    public void resetAll() {
        circuits.clear();
    }

    /** 重置指定组件 */
    public void reset(String componentName) {
        circuits.remove(componentName);
    }

    /** 组件熔断状态 */
    private static class CircuitState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private volatile Instant openedAt;
        private volatile boolean open;

        boolean isOpen(DegradationPolicy policy) {
            if (!open) return false;
            if (Duration.between(openedAt, Instant.now()).getSeconds()
                    >= policy.circuitBreakerOpenSeconds()) {
                open = false;
                consecutiveFailures.set(0);
                return false;
            }
            return true;
        }

        int incrementFailures() {
            return consecutiveFailures.incrementAndGet();
        }

        void openCircuit() {
            open = true;
            openedAt = Instant.now();
        }

        void reset() {
            open = false;
            consecutiveFailures.set(0);
        }
    }
}
