package com.yin.ai.platform.memory.audit;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryAuditLog")
class MemoryAuditLogTest {

    @Test
    @DisplayName("of 工厂方法创建完整审计记录")
    void shouldCreateAuditLog() {
        MemoryId id = MemoryId.of("mem-1", MemoryType.EPISODIC, "user-1");
        MemoryAuditLog log = MemoryAuditLog.of(
                MemoryAuditLog.AuditAction.APPEND, id,
                "agent-1", 100L, "tenant-1", "trace-001",
                3, Map.of("tokens", 500));

        assertThat(log.auditId()).isNotBlank();
        assertThat(log.actionType()).isEqualTo(MemoryAuditLog.AuditAction.APPEND);
        assertThat(log.memoryId()).isEqualTo(id);
        assertThat(log.memoryType()).isEqualTo(MemoryType.EPISODIC);
        assertThat(log.agentId()).isEqualTo("agent-1");
        assertThat(log.userId()).isEqualTo(100L);
        assertThat(log.tenantId()).isEqualTo("tenant-1");
        assertThat(log.traceId()).isEqualTo("trace-001");
        assertThat(log.cellCount()).isEqualTo(3);
        assertThat(log.timestamp()).isNotNull();
        assertThat(log.metadata()).containsEntry("tokens", 500);
    }

    @Test
    @DisplayName("null metadata → 空 Map")
    void shouldDefaultNullMetadata() {
        MemoryAuditLog log = MemoryAuditLog.of(
                MemoryAuditLog.AuditAction.READ, null, null, null, null, null, 0, null);
        assertThat(log.metadata()).isEmpty();
    }

    @Test
    @DisplayName("null memoryId 保底 type = null")
    void shouldHandleNullMemoryId() {
        MemoryAuditLog log = MemoryAuditLog.of(
                MemoryAuditLog.AuditAction.DELETE, null, "a", null, "t", "tr", 1, null);
        assertThat(log.memoryId()).isNull();
        assertThat(log.memoryType()).isNull();
    }

    @Test
    @DisplayName("AuditAction 枚举值完整")
    void shouldHaveAllAuditActions() {
        assertThat(MemoryAuditLog.AuditAction.values()).contains(
                MemoryAuditLog.AuditAction.READ,
                MemoryAuditLog.AuditAction.APPEND,
                MemoryAuditLog.AuditAction.BATCH_APPEND,
                MemoryAuditLog.AuditAction.UPDATE,
                MemoryAuditLog.AuditAction.DELETE,
                MemoryAuditLog.AuditAction.CAS_WRITE,
                MemoryAuditLog.AuditAction.CHECKPOINT_CREATE,
                MemoryAuditLog.AuditAction.CHECKPOINT_RESTORE,
                MemoryAuditLog.AuditAction.CONSOLIDATE,
                MemoryAuditLog.AuditAction.COMPRESS,
                MemoryAuditLog.AuditAction.EVICT,
                MemoryAuditLog.AuditAction.PROCEDURE_LEARNED,
                MemoryAuditLog.AuditAction.FACT_WRITTEN);
    }
}
