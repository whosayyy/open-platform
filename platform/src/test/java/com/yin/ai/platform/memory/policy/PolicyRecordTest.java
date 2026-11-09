package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Policy 记录模型")
class PolicyRecordTest {

    @Test
    @DisplayName("RetentionPolicy defaults 预置合理值")
    void retentionPolicyDefaults() {
        RetentionPolicy policy = RetentionPolicy.defaults();
        assertThat(policy.sessionTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(policy.episodicTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(policy.ttlFor(MemoryType.SEMANTIC)).isEqualTo(Duration.ofDays(365));
    }

    @Test
    @DisplayName("CompressionPolicy 工厂方法")
    void compressionPolicyFactories() {
        assertThat(CompressionPolicy.sizeTriggered(100).trigger())
                .isEqualTo(CompressionTrigger.SIZE);
        assertThat(CompressionPolicy.disabled().enabled()).isFalse();
    }

    @Test
    @DisplayName("ConsolidationPolicy disabled 不启用")
    void consolidationPolicyDisabled() {
        ConsolidationPolicy policy = ConsolidationPolicy.disabled();
        assertThat(policy.enabled()).isFalse();
    }

    @Test
    @DisplayName("EvictionPolicy defaults 设置合理水位线")
    void evictionPolicyDefaults() {
        EvictionPolicy policy = EvictionPolicy.defaults();
        assertThat(policy.highWatermark()).isEqualTo(10000);
        assertThat(policy.lowWatermark()).isEqualTo(8000);
        assertThat(policy.strategy()).isEqualTo(EvictionStrategy.FIFO);
    }

    @Test
    @DisplayName("MemoryPolicy 聚合默认值正确")
    void memoryPolicyAggregate() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        assertThat(policy.retention()).isNotNull();
        assertThat(policy.compression()).isNotNull();
        assertThat(policy.consolidation()).isNotNull();
        assertThat(policy.eviction()).isNotNull();
    }

    @Test
    @DisplayName("PolicyAction 工厂方法")
    void policyActionFactories() {
        var id = com.yin.ai.platform.core.memory.MemoryId.of("x", MemoryType.EPISODIC, "u");
        assertThat(PolicyAction.compress(id, "reason").actionType())
                .isEqualTo(PolicyAction.ActionType.COMPRESS);
        assertThat(PolicyAction.consolidate(id, "reason").actionType())
                .isEqualTo(PolicyAction.ActionType.CONSOLIDATE);
        assertThat(PolicyAction.evict(id, "reason").actionType())
                .isEqualTo(PolicyAction.ActionType.EVICT);
    }
}
