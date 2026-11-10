package com.yin.ai.platform.gateway.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link AuditLogStore} 的默认空实现 — 仅日志输出，不持久化。
 * <p>
 * 业务层可通过注册自定义 {@link AuditLogStore} Bean（如 JdbcAuditLogStore）来覆盖此默认实现。
 */
@Slf4j
@Component
@ConditionalOnMissingBean(AuditLogStore.class)
public class NoopAuditLogStore implements AuditLogStore {

    @Override
    public void save(AuditLogEntry entry) {
        log.info("[AUDIT] traceId={} caller={} model={} status={} promptTokens={} completionTokens={} latencyMs={}",
                entry.getTraceId(), entry.getCaller(), entry.getModel(), entry.getStatus(),
                entry.getPromptTokens(), entry.getCompletionTokens(), entry.getLatencyMs());
    }

    @Override
    public List<AuditLogEntry> query(AuditLogQuery query) {
        log.debug("[AUDIT] query ignored (noop store): traceId={} caller={}", query.traceId(), query.caller());
        return List.of();
    }

    @Override
    public long count(AuditLogQuery query) {
        return 0;
    }
}
