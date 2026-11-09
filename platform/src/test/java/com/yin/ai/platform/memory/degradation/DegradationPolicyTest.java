package com.yin.ai.platform.memory.degradation;

import com.yin.ai.platform.memory.degradation.DegradationPolicy.FailureAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DegradationPolicy")
class DegradationPolicyTest {

    @Test
    @DisplayName("semanticStore 预设策略：SKIP + 5s 超时")
    void shouldHaveSemanticStorePreset() {
        DegradationPolicy p = DegradationPolicy.semanticStore();
        assertThat(p.componentName()).isEqualTo("semantic-store");
        assertThat(p.failureAction()).isEqualTo(FailureAction.SKIP);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.maxConsecutiveFailures()).isEqualTo(5);
        assertThat(p.circuitBreakerOpenSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("embeddingService 预设策略：SKIP + 2s 超时")
    void shouldHaveEmbeddingServicePreset() {
        DegradationPolicy p = DegradationPolicy.embeddingService();
        assertThat(p.componentName()).isEqualTo("embedding-service");
        assertThat(p.failureAction()).isEqualTo(FailureAction.SKIP);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.maxConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("episodicStore 预设策略：FAIL_FAST")
    void shouldHaveEpisodicStorePreset() {
        DegradationPolicy p = DegradationPolicy.episodicStore();
        assertThat(p.componentName()).isEqualTo("episodic-store");
        assertThat(p.failureAction()).isEqualTo(FailureAction.FAIL_FAST);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("auditStore 预设策略：SKIP + 高容错")
    void shouldHaveAuditStorePreset() {
        DegradationPolicy p = DegradationPolicy.auditStore();
        assertThat(p.componentName()).isEqualTo("audit-store");
        assertThat(p.failureAction()).isEqualTo(FailureAction.SKIP);
        assertThat(p.maxConsecutiveFailures()).isEqualTo(10);
        assertThat(p.circuitBreakerOpenSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("compressionService 预设策略：SKIP + 10s 超时")
    void shouldHaveCompressionServicePreset() {
        DegradationPolicy p = DegradationPolicy.compressionService();
        assertThat(p.componentName()).isEqualTo("compression-service");
        assertThat(p.failureAction()).isEqualTo(FailureAction.SKIP);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("自定义策略参数")
    void shouldAcceptCustomParams() {
        DegradationPolicy p = new DegradationPolicy("custom", FailureAction.FALLBACK,
                Duration.ofSeconds(8), 7, 45);
        assertThat(p.componentName()).isEqualTo("custom");
        assertThat(p.failureAction()).isEqualTo(FailureAction.FALLBACK);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.maxConsecutiveFailures()).isEqualTo(7);
        assertThat(p.circuitBreakerOpenSeconds()).isEqualTo(45);
    }

    @Test
    @DisplayName("null timeout 使用默认 5s")
    void shouldDefaultTimeout() {
        DegradationPolicy p = new DegradationPolicy("test", FailureAction.SKIP, null, 3, 30);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("非法参数自动修正")
    void shouldFixInvalidParams() {
        DegradationPolicy p = new DegradationPolicy("test", FailureAction.SKIP,
                null, -1, 0);
        assertThat(p.maxConsecutiveFailures()).isEqualTo(5);
        assertThat(p.circuitBreakerOpenSeconds()).isEqualTo(30);
    }
}
