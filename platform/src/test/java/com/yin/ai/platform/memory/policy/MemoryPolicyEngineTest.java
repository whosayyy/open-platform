package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryPolicyEngine")
class MemoryPolicyEngineTest {

    private MemoryPolicyEngine engine;
    private MemoryId memoryId;

    @BeforeEach
    void setUp() {
        engine = new MemoryPolicyEngine();
        memoryId = MemoryId.of("test-ep", MemoryType.EPISODIC, "user-1");
    }

    @Test
    @DisplayName("Cell 超限触发 COMPRESS（SIZE 模式）")
    void shouldTriggerCompressOnSizeThreshold() {
        CompressionPolicy cp = CompressionPolicy.sizeTriggered(100);
        MemoryPolicy policy = new MemoryPolicy(null, cp, ConsolidationPolicy.disabled(),
                EvictionPolicy.disabled());
        MemoryStats stats = statsWith(150);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);

        assertThat(actions).anyMatch(a -> a.actionType() == PolicyAction.ActionType.COMPRESS);
    }

    @Test
    @DisplayName("Cell 不足不触发压缩")
    void shouldNotTriggerCompressUnderLimit() {
        CompressionPolicy cp = CompressionPolicy.sizeTriggered(500);
        MemoryPolicy policy = new MemoryPolicy(null, cp, ConsolidationPolicy.disabled(),
                EvictionPolicy.disabled());
        MemoryStats stats = statsWith(100);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);
        assertThat(actions).noneMatch(a -> a.actionType() == PolicyAction.ActionType.COMPRESS);
    }

    @Test
    @DisplayName("disabled 压缩策略不触发")
    void shouldNotTriggerWhenCompressionDisabled() {
        MemoryPolicy policy = new MemoryPolicy(null, CompressionPolicy.disabled(),
                ConsolidationPolicy.disabled(), EvictionPolicy.disabled());
        MemoryStats stats = statsWith(1000);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);
        assertThat(actions).noneMatch(a -> a.actionType() == PolicyAction.ActionType.COMPRESS);
    }

    @Test
    @DisplayName("Cell 数达标触发 CONSOLIDATE")
    void shouldTriggerConsolidate() {
        ConsolidationPolicy cp = new ConsolidationPolicy(Duration.ofHours(1),
                ConsolidationScope.ALL, 50, true);
        MemoryPolicy policy = new MemoryPolicy(null, CompressionPolicy.disabled(),
                cp, EvictionPolicy.disabled());
        MemoryStats stats = statsWith(60);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);

        assertThat(actions).anyMatch(a -> a.actionType() == PolicyAction.ActionType.CONSOLIDATE);
    }

    @Test
    @DisplayName("Cell 不足不触发 Consolidation")
    void shouldNotTriggerConsolidate() {
        ConsolidationPolicy cp = new ConsolidationPolicy(Duration.ofHours(1),
                ConsolidationScope.ALL, 50, true);
        MemoryPolicy policy = new MemoryPolicy(null, CompressionPolicy.disabled(),
                cp, EvictionPolicy.disabled());
        MemoryStats stats = statsWith(10);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);
        assertThat(actions).noneMatch(a -> a.actionType() == PolicyAction.ActionType.CONSOLIDATE);
    }

    @Test
    @DisplayName("超水位线触发 EVICT")
    void shouldTriggerEviction() {
        EvictionPolicy ep = new EvictionPolicy(EvictionStrategy.FIFO, 1000, 800,
                MemoryType.EPISODIC, true);
        MemoryPolicy policy = new MemoryPolicy(null, CompressionPolicy.disabled(),
                ConsolidationPolicy.disabled(), ep);
        MemoryStats stats = statsWith(1200);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);

        assertThat(actions).anyMatch(a -> a.actionType() == PolicyAction.ActionType.EVICT);
    }

    @Test
    @DisplayName("未超水位线不触发淘汰")
    void shouldNotTriggerEviction() {
        EvictionPolicy ep = new EvictionPolicy(EvictionStrategy.FIFO, 1000, 800,
                MemoryType.EPISODIC, true);
        MemoryPolicy policy = new MemoryPolicy(null, CompressionPolicy.disabled(),
                ConsolidationPolicy.disabled(), ep);
        MemoryStats stats = statsWith(500);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);
        assertThat(actions).noneMatch(a -> a.actionType() == PolicyAction.ActionType.EVICT);
    }

    @Test
    @DisplayName("多项同时触发返回多个操作")
    void shouldReturnMultipleActions() {
        MemoryPolicy policy = new MemoryPolicy(
                null,
                CompressionPolicy.sizeTriggered(50),
                new ConsolidationPolicy(Duration.ofHours(1), ConsolidationScope.ALL, 10, true),
                new EvictionPolicy(EvictionStrategy.FIFO, 100, 80, MemoryType.EPISODIC, true));
        MemoryStats stats = statsWith(150);

        List<PolicyAction> actions = engine.evaluate(memoryId, policy, stats);

        assertThat(actions).hasSize(3);
    }

    private static MemoryStats statsWith(long totalCells) {
        return new MemoryStats(1, totalCells, Map.of(), 0, 0, 0);
    }
}
