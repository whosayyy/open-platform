package com.yin.ai.platform.memory.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryMigrator")
class MemoryMigratorTest {

    @Test
    @DisplayName("MigrationResult.success 工厂方法")
    void migrationResultSuccess() {
        MemoryMigrator.MigrationResult result = MemoryMigrator.MigrationResult.success(42);
        assertThat(result.success()).isTrue();
        assertThat(result.recordsMigrated()).isEqualTo(42);
        assertThat(result.message()).contains("42");
    }

    @Test
    @DisplayName("MigrationResult.failure 工厂方法")
    void migrationResultFailure() {
        MemoryMigrator.MigrationResult result =
                MemoryMigrator.MigrationResult.failure("schema mismatch");
        assertThat(result.success()).isFalse();
        assertThat(result.recordsMigrated()).isZero();
        assertThat(result.message()).isEqualTo("schema mismatch");
    }

    @Test
    @DisplayName("MigrationResult.noop 工厂方法")
    void migrationResultNoop() {
        MemoryMigrator.MigrationResult result = MemoryMigrator.MigrationResult.noop();
        assertThat(result.success()).isTrue();
        assertThat(result.recordsMigrated()).isZero();
        assertThat(result.message()).contains("No records");
    }

    @Test
    @DisplayName("SPI 接口方法签名正确")
    void spiInterfaceMethods() {
        // 验证 SPI 接口有 fromVersion 和 toVersion 方法
        MemoryMigrator dummy = new MemoryMigrator() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public int toVersion() {
                return 2;
            }

            @Override
            public MigrationResult migrate(com.yin.ai.platform.core.memory.store.MemoryStore store) {
                return MigrationResult.noop();
            }
        };

        assertThat(dummy.fromVersion()).isEqualTo(1);
        assertThat(dummy.toVersion()).isEqualTo(2);
    }
}
