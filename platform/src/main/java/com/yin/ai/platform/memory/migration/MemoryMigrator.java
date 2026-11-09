package com.yin.ai.platform.memory.migration;

import com.yin.ai.platform.core.memory.store.MemoryStore;

/**
 * Memory 迁移器 SPI — 负责将数据从一个版本迁移到下一个版本。
 * <p>
 * 每个实现负责一个版本跨度的迁移（如 V1→V2），多个迁移器可串联执行。
 * 迁移必须是幂等的：重复执行不会产生副作用。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>{@link #fromVersion()} &lt; {@link #toVersion()}，单调递增</li>
 *   <li>{@link #migrate(MemoryStore)} 遍历 Store 中所有 MemoryId 并逐条迁移 Cell</li>
 *   <li>迁移失败抛出 MemoryMigrationException，由调用方决定回滚或跳过</li>
 * </ul>
 */
public interface MemoryMigrator {

    /** 迁移起始版本（不含） */
    int fromVersion();

    /** 迁移目标版本（含） */
    int toVersion();

    /**
     * 执行迁移 — 从 {@link #fromVersion()} 迁移到 {@link #toVersion()}。
     *
     * @param store 待迁移的 MemoryStore
     * @return 迁移结果
     */
    MigrationResult migrate(MemoryStore store);

    /**
     * 迁移结果。
     *
     * @param success         是否成功
     * @param recordsMigrated 迁移记录数
     * @param message         结果描述
     */
    record MigrationResult(boolean success, int recordsMigrated, String message) {

        public static MigrationResult success(int recordsMigrated) {
            return new MigrationResult(true, recordsMigrated,
                    "Successfully migrated " + recordsMigrated + " records");
        }

        public static MigrationResult failure(String reason) {
            return new MigrationResult(false, 0, reason);
        }

        public static MigrationResult noop() {
            return new MigrationResult(true, 0, "No records to migrate");
        }
    }
}
