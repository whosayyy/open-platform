package com.yin.ai.platform.memory.audit;

import java.time.Instant;
import java.util.List;

/**
 * Memory 审计存储 SPI — 持久化和查询审计日志。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>append-only：只追加，不修改，不删除</li>
 *   <li>异步写入：审计记录不应阻塞主流程</li>
 *   <li>TTL 管理：按 retention 策略自动清理过期记录</li>
 *   <li>合规查询：支持按 userId / tenantId / traceId / 时间范围查询</li>
 * </ul>
 * <p>
 * 实现：
 * <ul>
 *   <li>生产：MySQL/ES（支持按维度索引查询）</li>
 *   <li>开发：InMemory（ConcurrentLinkedQueue + 环形缓冲）</li>
 * </ul>
 */
public interface MemoryAuditStore {

    /** 追加一条审计记录 */
    void record(MemoryAuditLog log);

    /** 批量追加 */
    void recordBatch(List<MemoryAuditLog> logs);

    /** 按用户查询 */
    List<MemoryAuditLog> findByUser(Long userId, Instant from, Instant to, int limit);

    /** 按租户查询 */
    List<MemoryAuditLog> findByTenant(String tenantId, Instant from, Instant to, int limit);

    /** 按链路追踪 ID 查询 */
    List<MemoryAuditLog> findByTraceId(String traceId);

    /** 按 Memory ID 查询全部操作历史 */
    List<MemoryAuditLog> findByMemoryId(String memoryId, int limit);

    /** 清理过期记录（由 MemoryScheduler 定时调用） */
    int purgeBefore(Instant cutoff);

    /** 总记录数 */
    long count();
}
