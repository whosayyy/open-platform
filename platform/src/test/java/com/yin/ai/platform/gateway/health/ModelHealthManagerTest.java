package com.yin.ai.platform.gateway.health;

import com.yin.ai.platform.gateway.model.DefaultModelRegistry;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.router.strategy.RouterContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelHealthManager — 模型健康管理器")
class ModelHealthManagerTest {

    private ModelHealthManager manager;
    private RouterContext ctx;
    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        ModelDefinition model = ModelDefinition.builder()
                .name("test-model")
                .provider("test")
                .enabled(true)
                .build();
        registry = new DefaultModelRegistry(List.of(model), "test-model");
        ctx = new RouterContext();
        manager = new ModelHealthManager(registry, ctx, 3, 1000);
    }

    @Test
    @DisplayName("记录成功时同步更新 RouterContext")
    void shouldSyncSuccessToRouterContext() {
        manager.recordSuccess("test-model");
        assertThat(ctx.isHealthy("test-model")).isTrue();
    }

    @Test
    @DisplayName("记录失败时更新错误率和健康状态")
    void shouldSyncFailureToRouterContext() {
        manager.recordFailure("test-model");
        assertThat(ctx.getErrorRate("test-model")).isGreaterThan(0);
    }

    @Test
    @DisplayName("连续失败触发熔断后模型标记为不健康")
    void shouldMarkUnhealthyOnCircuitOpen() {
        manager.recordFailure("test-model");
        manager.recordFailure("test-model");
        manager.recordFailure("test-model");

        assertThat(ctx.isHealthy("test-model")).isFalse();
        CircuitBreakerState breaker = manager.getBreaker("test-model");
        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.State.OPEN);
    }

    @Test
    @DisplayName("runHealthCheck 将过期 OPEN 状态转为 HALF_OPEN")
    void shouldTransitionToHalfOpen() throws InterruptedException {
        ModelHealthManager fastManager = new ModelHealthManager(registry, ctx, 3, 10);
        for (int i = 0; i < 3; i++) fastManager.recordFailure("test-model");
        assertThat(fastManager.getBreaker("test-model").getState())
                .isEqualTo(CircuitBreakerState.State.OPEN);

        Thread.sleep(30);
        fastManager.runHealthCheck();
        assertThat(fastManager.getBreaker("test-model").getState())
                .isEqualTo(CircuitBreakerState.State.HALF_OPEN);
        assertThat(ctx.isHealthy("test-model")).isTrue();
    }

    @Test
    @DisplayName("reset 重置熔断器并恢复健康状态")
    void shouldResetBreaker() {
        for (int i = 0; i < 3; i++) manager.recordFailure("test-model");
        manager.reset("test-model");

        assertThat(ctx.isHealthy("test-model")).isTrue();
        assertThat(manager.getBreaker("test-model").getState())
                .isEqualTo(CircuitBreakerState.State.CLOSED);
    }

    @Test
    @DisplayName("未知模型返回 null breaker")
    void shouldReturnNullForUnknownModel() {
        assertThat(manager.getBreaker("non-existent")).isNull();
    }

    @Test
    @DisplayName("getBreakerStates 返回快照")
    void shouldReturnBreakerStates() {
        manager.recordSuccess("test-model");
        var states = manager.getBreakerStates();
        assertThat(states).containsKey("test-model");
        assertThat(states.get("test-model")).isEqualTo(CircuitBreakerState.State.CLOSED);
    }
}
