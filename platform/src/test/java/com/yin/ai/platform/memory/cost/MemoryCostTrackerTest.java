package com.yin.ai.platform.memory.cost;

import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryCostTracker")
class MemoryCostTrackerTest {

    private MemoryCostTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new MemoryCostTracker();
    }

    @Test
    @DisplayName("单条记录聚合")
    void shouldRecordSingleUsage() {
        MemoryUsageRecord r = MemoryUsageRecord.of(
                "tenant-1", 100L, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND,
                500, 200, 1024, 3, 150, null);

        tracker.record(r);

        MemoryCostTracker.TenantUsage usage = tracker.getUsage("tenant-1");
        assertThat(usage.totalInputTokens()).isEqualTo(500);
        assertThat(usage.totalOutputTokens()).isEqualTo(200);
        assertThat(usage.totalApiCalls()).isEqualTo(1);
        assertThat(usage.totalStorageBytes()).isEqualTo(1024);
        assertThat(usage.totalCellCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("多条记录累加")
    void shouldAccumulateMultipleRecords() {
        for (int i = 0; i < 5; i++) {
            tracker.record(MemoryUsageRecord.of(
                    "tenant-1", null, MemoryType.EPISODIC,
                    MemoryUsageRecord.OperationType.READ,
                    100, 50, 0, 1, 10, null));
        }

        MemoryCostTracker.TenantUsage usage = tracker.getUsage("tenant-1");
        assertThat(usage.totalInputTokens()).isEqualTo(500);
        assertThat(usage.totalOutputTokens()).isEqualTo(250);
        assertThat(usage.totalApiCalls()).isEqualTo(5);
        assertThat(usage.totalTokens()).isEqualTo(750);
    }

    @Test
    @DisplayName("null tenantId → default")
    void shouldDefaultTenant() {
        tracker.record(MemoryUsageRecord.of(
                null, null, MemoryType.SESSION,
                MemoryUsageRecord.OperationType.APPEND,
                10, 5, 0, 1, 1, null));

        MemoryCostTracker.TenantUsage usage = tracker.getUsage("default");
        assertThat(usage.totalApiCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("多租户隔离")
    void shouldIsolateTenants() {
        tracker.record(MemoryUsageRecord.of("tenant-A", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 100, 50, 0, 1, 1, null));
        tracker.record(MemoryUsageRecord.of("tenant-B", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 200, 100, 0, 1, 1, null));

        assertThat(tracker.getUsage("tenant-A").totalInputTokens()).isEqualTo(100);
        assertThat(tracker.getUsage("tenant-B").totalInputTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("不存在的租户返回 zero")
    void shouldReturnZeroForUnknownTenant() {
        MemoryCostTracker.TenantUsage usage = tracker.getUsage("nonexistent");
        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.totalApiCalls()).isZero();
    }

    @Test
    @DisplayName("EMBED 操作统计 embeddingTokens")
    void shouldTrackEmbeddingTokens() {
        tracker.record(MemoryUsageRecord.of(
                "tenant-1", null, MemoryType.SEMANTIC,
                MemoryUsageRecord.OperationType.EMBED,
                800, 0, 512, 1, 50, null));

        MemoryCostTracker.TenantUsage usage = tracker.getUsage("tenant-1");
        assertThat(usage.totalEmbeddingTokens()).isEqualTo(800);
        assertThat(usage.totalInputTokens()).isEqualTo(800);
        assertThat(usage.totalTokens()).isEqualTo(1600);
    }

    @Test
    @DisplayName("批量记录")
    void shouldRecordBatch() {
        List<MemoryUsageRecord> records = List.of(
                MemoryUsageRecord.of("t1", null, MemoryType.EPISODIC,
                        MemoryUsageRecord.OperationType.READ, 10, 5, 0, 1, 1, null),
                MemoryUsageRecord.of("t1", null, MemoryType.EPISODIC,
                        MemoryUsageRecord.OperationType.READ, 20, 10, 0, 1, 1, null));

        tracker.recordBatch(records);
        assertThat(tracker.getUsage("t1").totalInputTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("getAllUsage 返回所有租户")
    void shouldReturnAllUsage() {
        tracker.record(MemoryUsageRecord.of("a", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 1, 0, 0, 1, 1, null));
        tracker.record(MemoryUsageRecord.of("b", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 2, 0, 0, 1, 1, null));

        Map<String, MemoryCostTracker.TenantUsage> all = tracker.getAllUsage();
        assertThat(all).containsKeys("a", "b");
        assertThat(all.get("a").totalInputTokens()).isEqualTo(1);
        assertThat(all.get("b").totalInputTokens()).isEqualTo(2);
    }

    @Test
    @DisplayName("reset 重置并返回快照")
    void shouldResetAndReturnSnapshot() {
        tracker.record(MemoryUsageRecord.of("t1", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 100, 50, 0, 1, 1, null));

        MemoryCostTracker.TenantUsage snapshot = tracker.reset("t1");
        assertThat(snapshot.totalInputTokens()).isEqualTo(100);

        // reset 后归零
        assertThat(tracker.getUsage("t1").totalTokens()).isZero();
    }

    @Test
    @DisplayName("operationsByType 按操作类型统计")
    void shouldCountOperationsByType() {
        tracker.record(MemoryUsageRecord.of("t1", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 1, 0, 0, 1, 1, null));
        tracker.record(MemoryUsageRecord.of("t1", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND, 1, 0, 0, 1, 1, null));
        tracker.record(MemoryUsageRecord.of("t1", null, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.READ, 1, 0, 0, 1, 1, null));

        MemoryCostTracker.TenantUsage usage = tracker.getUsage("t1");
        assertThat(usage.operationsByType()).containsEntry("APPEND", 2L);
        assertThat(usage.operationsByType()).containsEntry("READ", 1L);
    }
}
