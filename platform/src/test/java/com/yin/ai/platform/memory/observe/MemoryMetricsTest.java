package com.yin.ai.platform.memory.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryMetrics")
class MemoryMetricsTest {

    private MeterRegistry registry;
    private MemoryMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MemoryMetrics(registry);
    }

    @Test
    @DisplayName("recordAppend 增加计数和耗时")
    void shouldRecordAppend() {
        metrics.recordAppend("EPISODIC", 42);
        Counter counter = registry.find("ai.memory.append.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordRead 增加计数")
    void shouldRecordRead() {
        metrics.recordRead("WORKING", 10);
        Counter counter = registry.find("ai.memory.read.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordCacheHit/Miss 计数")
    void shouldRecordCache() {
        metrics.recordCacheHit();
        metrics.recordCacheHit();
        metrics.recordCacheMiss();

        assertThat(registry.find("ai.memory.cache.hit").counter().count()).isEqualTo(2.0);
        assertThat(registry.find("ai.memory.cache.miss").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordCasConflict 计数")
    void shouldRecordCasConflict() {
        metrics.recordCasConflict();
        metrics.recordCasConflict();
        assertThat(registry.find("ai.memory.cas.conflict").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordStoreError 按 store 类型分维度")
    void shouldRecordStoreError() {
        metrics.recordStoreError("REDIS");
        metrics.recordStoreError("REDIS");
        metrics.recordStoreError("MYSQL");

        assertThat(registry.find("ai.memory.store.error").counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("recordEventPublished 按事件类型分维度")
    void shouldRecordEventPublished() {
        metrics.recordEventPublished("APPENDED");
        metrics.recordEventPublished("APPENDED");
        metrics.recordEventPublished("CONSOLIDATED");

        assertThat(registry.find("ai.memory.event.published").counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("recordActiveMemoryCount 写入 Gauge")
    void shouldRecordActiveMemoryGauge() {
        metrics.recordActiveMemoryCount(42);
        assertThat(registry.find("ai.memory.active.count").gauge().value()).isEqualTo(42.0);
    }

    @Test
    @DisplayName("recordTotalCellCount 写入 Gauge")
    void shouldRecordTotalCellGauge() {
        metrics.recordTotalCellCount(1000);
        assertThat(registry.find("ai.memory.cell.count").gauge().value()).isEqualTo(1000.0);
    }
}
