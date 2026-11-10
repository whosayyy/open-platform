package com.yin.ai.platform.gateway.audit;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志存储 SPI。
 */
public interface AuditLogStore {

    /** 保存审计日志 */
    void save(AuditLogEntry entry);

    /** 查询审计日志 */
    List<AuditLogEntry> query(AuditLogQuery query);

    /** 统计数量 */
    long count(AuditLogQuery query);

    /** 审计日志查询条件 */
    record AuditLogQuery(
            String traceId,
            String caller,
            String model,
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int page,
            int size
    ) {}
}
