package com.yin.ai.platform.memory.cost;

import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryUsageRecord")
class MemoryUsageRecordTest {

    @Test
    @DisplayName("of 工厂方法创建完整记录")
    void shouldCreateWithFactory() {
        MemoryUsageRecord r = MemoryUsageRecord.of(
                "tenant-1", 100L, MemoryType.EPISODIC,
                MemoryUsageRecord.OperationType.APPEND,
                500, 200, 1024, 3, 150,
                Map.of("model", "gpt-4"));

        assertThat(r.tenantId()).isEqualTo("tenant-1");
        assertThat(r.userId()).isEqualTo(100L);
        assertThat(r.memoryType()).isEqualTo(MemoryType.EPISODIC);
        assertThat(r.operationType()).isEqualTo(MemoryUsageRecord.OperationType.APPEND);
        assertThat(r.inputTokens()).isEqualTo(500);
        assertThat(r.outputTokens()).isEqualTo(200);
        assertThat(r.storageBytes()).isEqualTo(1024);
        assertThat(r.cellCount()).isEqualTo(3);
        assertThat(r.durationMs()).isEqualTo(150);
        assertThat(r.totalTokens()).isEqualTo(700);
        assertThat(r.recordId()).isNotBlank();
        assertThat(r.timestamp()).isNotNull();
        assertThat(r.metadata()).containsEntry("model", "gpt-4");
    }

    @Test
    @DisplayName("null metadata → 空 Map")
    void shouldDefaultNullMetadata() {
        MemoryUsageRecord r = MemoryUsageRecord.of(
                "t1", null, MemoryType.SESSION,
                MemoryUsageRecord.OperationType.READ,
                0, 0, 0, 0, 0, null);
        assertThat(r.metadata()).isEmpty();
    }

    @Test
    @DisplayName("totalTokens = input + output")
    void shouldSumTotalTokens() {
        MemoryUsageRecord r = MemoryUsageRecord.of(
                "t1", null, MemoryType.WORKING,
                MemoryUsageRecord.OperationType.CONSOLIDATE,
                1000, 500, 0, 1, 100, null);
        assertThat(r.totalTokens()).isEqualTo(1500);
    }

    @Test
    @DisplayName("所有 OperationType 均可创建")
    void shouldSupportAllOperationTypes() {
        for (MemoryUsageRecord.OperationType op : MemoryUsageRecord.OperationType.values()) {
            MemoryUsageRecord r = MemoryUsageRecord.of(
                    "t1", null, MemoryType.EPISODIC, op, 0, 0, 0, 0, 0, null);
            assertThat(r.operationType()).isEqualTo(op);
        }
    }

    @Test
    @DisplayName("EMBED 操作类型")
    void shouldTrackEmbedOperation() {
        MemoryUsageRecord r = MemoryUsageRecord.of(
                "t1", 200L, MemoryType.SEMANTIC,
                MemoryUsageRecord.OperationType.EMBED,
                800, 0, 512, 1, 50, Map.of("dimension", 1536));
        assertThat(r.operationType()).isEqualTo(MemoryUsageRecord.OperationType.EMBED);
        assertThat(r.inputTokens()).isEqualTo(800);
    }
}
