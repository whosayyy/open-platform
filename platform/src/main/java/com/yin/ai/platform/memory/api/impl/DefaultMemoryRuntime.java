package com.yin.ai.platform.memory.api.impl;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemorySnapshot;
import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemorySpan;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.memory.config.MemoryProperties;
import com.yin.ai.platform.memory.exception.MemoryConcurrencyException;
import com.yin.ai.platform.memory.exception.MemoryNotFoundException;
import com.yin.ai.platform.memory.search.ElasticsearchMemorySearchService;
import com.yin.ai.platform.memory.retrieval.HybridRetriever;
import com.yin.ai.platform.memory.retrieval.VectorMemoryRetriever;
import com.yin.ai.platform.core.memory.semantic.EmbeddingService;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import com.yin.ai.platform.core.memory.working.PlanNode;
import com.yin.ai.platform.core.memory.working.PlanNodeStatus;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import com.yin.ai.platform.memory.working.WorkingMemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MemoryRuntime} 的默认实现 — 通过可配置的 {@link EpisodicMemoryStore} 和
 * {@link WorkingMemoryManager} 提供完整的 Memory 操作能力。
 * <p>
 * 高并发设计要点：
 * <ul>
 *   <li>Episodic 存储完全委托给 {@link EpisodicMemoryStore}（支持 InMemory / Redis / Tiered），
 *       不再使用本地 ConcurrentHashMap</li>
 *   <li>{@link #casAppend} 方法使用乐观锁（CAS）写入，版本冲突时自动重试（最多 3 次），
 *       支持多实例并发安全更新</li>
 *   <li>{@link #append} 作为普通追加（无 CAS），适用于高吞吐的只追加场景（Timeline）</li>
 *   <li>检索通过 {@link EpisodicMemoryStore#readAll} 拉取全量在内存中过滤，
 *       生产环境应替换为 Vector Store 混合检索</li>
 * </ul>
 * <p>
 * 分布式特征：当 {@link EpisodicMemoryStore} 为 Redis 实现时，
 * 多实例自动共享同一数据源，无需额外同步。
 */
public class DefaultMemoryRuntime implements MemoryRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryRuntime.class);
    private static final int MAX_CAS_RETRIES = 3;

    private final WorkingMemoryManager workingMemoryManager;
    private final EpisodicMemoryStore episodicStore;
    private final Set<MemoryId> memoryIdRegistry = ConcurrentHashMap.newKeySet();
    private final HybridRetriever hybridRetriever =
            new HybridRetriever(new VectorMemoryRetriever());

    private EmbeddingService embeddingService;
    private MemoryProperties.Retrieval retrievalProperties = new MemoryProperties.Retrieval();

    /** ES 全文检索服务（可选）。为 null 时降级为内存遍历。 */
    private ElasticsearchMemorySearchService esSearchService;

    /** Memory 审计存储（可选，P1-2：接入后写操作留痕 ai_memory_audit_log）。 */
    private com.yin.ai.platform.memory.audit.MemoryAuditStore auditStore;

    /** [全链路追踪] 环节记录器（可选，service 层 JDBC 实现；未装配时 NOOP）。 */
    private com.yin.ai.platform.agent.trace.StepTraceRecorder stepTraceRecorder =
            com.yin.ai.platform.agent.trace.StepTraceRecorder.NOOP;

    public DefaultMemoryRuntime(WorkingMemoryManager workingMemoryManager,
                                 EpisodicMemoryStore episodicStore) {
        this.workingMemoryManager = workingMemoryManager;
        this.episodicStore = episodicStore;
    }

    /** [全链路追踪] 可选注入环节记录器。 */
    public void setStepTraceRecorder(com.yin.ai.platform.agent.trace.StepTraceRecorder recorder) {
        this.stepTraceRecorder = recorder != null ? recorder
                : com.yin.ai.platform.agent.trace.StepTraceRecorder.NOOP;
    }

    /** [全链路追踪] 记录一次 Memory 环节（写入/读取）。 */
    private void recordStepTrace(MemoryContext ctx, String stage, String detail, long durationMs) {
        if (ctx == null) {
            return;
        }
        stepTraceRecorder.record(ctx.traceId(), stage, detail, durationMs);
    }

    /** 可选注入 ES 检索服务，启用后 retrieve() 走 ES match 查询。 */
    public void setEsSearchService(ElasticsearchMemorySearchService esSearchService) {
        this.esSearchService = esSearchService;
        log.info("[MEM] Elasticsearch memory search enabled");
    }

    /** 可选启用新记忆向量化和查询向量化。 */
    public void setEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        log.info("[MEM] Memory embedding enabled");
    }

    public void setRetrievalProperties(MemoryProperties.Retrieval properties) {
        if (properties != null) {
            this.retrievalProperties = properties;
        }
    }

    /** 可选注入审计存储（P1-2：写操作留痕）。 */
    public void setAuditStore(com.yin.ai.platform.memory.audit.MemoryAuditStore auditStore) {
        this.auditStore = auditStore;
        log.info("[MEM] Memory audit store enabled");
    }

    /** 审计写操作（异步语义：失败仅 warn，不阻塞主流程）。 */
    private void audit(com.yin.ai.platform.memory.audit.MemoryAuditLog.AuditAction action,
                       MemoryId id, int cellCount, String traceId) {
        if (auditStore == null) {
            return;
        }
        try {
            auditStore.record(com.yin.ai.platform.memory.audit.MemoryAuditLog.of(
                    action, id, null, null, null, traceId, cellCount, Map.of()));
        } catch (Exception e) {
            log.debug("[MEM] audit failed: {}", e.getMessage());
        }
    }

    // ==================== Working Memory ====================

    @Override
    public WorkingMemory getOrCreateWorkingMemory(MemoryContext ctx) {
        return workingMemoryManager.create(ctx);
    }

    @Override
    public Optional<WorkingMemory> loadWorkingMemory(MemoryId id) {
        return workingMemoryManager.load(id);
    }

    @Override
    public void saveWorkingMemory(WorkingMemory wm) {
        workingMemoryManager.save(wm);
    }

    @Override
    public ResumePoint createResumePoint(MemoryId id, String label) {
        WorkingMemory wm = workingMemoryManager.load(id)
                .orElseThrow(() -> new MemoryNotFoundException("WorkingMemory not found: " + id));
        ResumePoint point = wm.createResumePoint(label);
        workingMemoryManager.saveResumePoint(wm, point);
        return point;
    }

    @Override
    public Optional<WorkingMemory> resumeFromPoint(MemoryId id, String resumePointLabel,
                                                    MemoryContext ctx) {
        return workingMemoryManager.resume(id, resumePointLabel);
    }

    // ==================== Episodic Memory ====================

    @Override
    public MemoryCell append(MemoryId id, String content, MemoryContext ctx) {
        return append(id, content, Map.of(), ctx);
    }

    @Override
    public MemoryCell append(MemoryId id, String content, Map<String, Object> attributes,
                             MemoryContext ctx) {
        long startMs = System.currentTimeMillis();
        try {
            MemoryCell cell = MemoryCell.of(
                    MemoryCellId.of(UUID.randomUUID().toString()),
                    id.type(),
                    content,
                    attributes != null ? attributes : Map.of());
            cell = prepareForStorage(cell);
            memoryIdRegistry.add(id);
            MemoryCell stored = episodicStore.append(id, cell);

            // Write-through: 同步写入 ES 全文索引（失败不影响主流程）
            if (esSearchService != null) {
                esSearchService.indexCell(id, stored);
            }
            audit(com.yin.ai.platform.memory.audit.MemoryAuditLog.AuditAction.APPEND,
                    id, 1, ctx != null ? ctx.traceId() : null);
            return stored;
        } finally {
            // [全链路追踪] 记忆写入环节埋点
            recordStepTrace(ctx, "MEMORY_WRITE",
                    "append type=" + id.type() + " len=" + (content != null ? content.length() : 0),
                    System.currentTimeMillis() - startMs);
        }
    }

    @Override
    public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells, MemoryContext ctx) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        // P0-2 修复：批量追加同样注册 registry（stats/内存检索可见）并写透 ES（语义检索可见）
        memoryIdRegistry.add(id);
        List<MemoryCell> prepared = cells.stream().map(this::prepareForStorage).toList();
        List<MemoryCell> stored = episodicStore.appendBatch(id, prepared);
        if (esSearchService != null) {
            for (MemoryCell cell : stored) {
                esSearchService.indexCell(id, cell);
            }
        }
        audit(com.yin.ai.platform.memory.audit.MemoryAuditLog.AuditAction.BATCH_APPEND,
                id, stored.size(), ctx != null ? ctx.traceId() : null);
        return stored;
    }

    /**
     * CAS 乐观锁追加 — 适用于需要并发控制的更新场景。
     * 先读取当前版本，执行业务逻辑生成新 Cell，再用 CAS 写入。
     * 版本冲突时自动重试（最多 {@value #MAX_CAS_RETRIES} 次）。
     *
     * @throws MemoryConcurrencyException 超过最大重试次数仍冲突时抛出
     */
    public MemoryCell casAppend(MemoryId id, MemoryCellId cellId, String content,
                                 MemoryContext ctx) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            List<MemoryCell> existing = episodicStore.readAll(id);
            Optional<MemoryCell> previous = existing.stream()
                    .filter(c -> c.cellId().equals(cellId))
                    .findFirst();
            int currentVersion = previous.map(MemoryCell::version).orElse(0);

            // P2-2 修复：写入的 cell 直接携带新版本号（currentVersion+1），
            // 使 timeline RList 中存储的 JSON 版本与 CAS hash 版本一致，读取反序列化不再分叉
            MemoryCell cell = previous
                    .map(value -> value.withContent(content).withVersion(currentVersion + 1))
                    .orElseGet(() -> MemoryCell.of(cellId, id.type(), content)
                            .withVersion(currentVersion + 1));
            cell = prepareForStorage(cell);

            if (episodicStore.casWrite(id, cell, currentVersion)) {
                audit(com.yin.ai.platform.memory.audit.MemoryAuditLog.AuditAction.CAS_WRITE,
                        id, 1, ctx != null ? ctx.traceId() : null);
                return cell;
            }
        }
        throw new MemoryConcurrencyException(
                "CAS append failed after " + MAX_CAS_RETRIES + " retries for " + cellId);
    }

    // ==================== Retrieval ====================

    @Override
    public QueryResult retrieve(MemoryQuery query, MemoryContext ctx) {
        long startMs = System.currentTimeMillis();
        try {
            if (query == null) {
                return QueryResult.of(List.of(), 0, Duration.ZERO);
            }
            String ownerKey = ctx != null && ctx.userId() != null ? ctx.userId().toString() : null;
            MemoryQuery effectiveQuery = enrichQueryVector(query);
            List<List<ScoredMemoryCell>> routes = new ArrayList<>();

            // Route 1: ES BM25（生产主索引）
            if (esSearchService != null && query.textQuery() != null && !query.textQuery().isBlank()) {
                List<ScoredMemoryCell> esResults = esSearchService.search(
                        effectiveQuery.withTopK(Math.max(query.topK(), query.topK() * 2)), ownerKey)
                        .stream()
                        .map(result -> new ScoredMemoryCell(result.cell(), result.score(),
                                result.sourceType(), "ES_BM25"))
                        .toList();
                if (!esResults.isEmpty()) {
                    routes.add(esResults);
                }
            }

            // Route 2/3: 本地关键词 + 向量，由 HybridRetriever 内部先做 RRF。
            List<MemoryCell> localCandidates = collectCandidateCells(effectiveQuery, ownerKey);
            List<ScoredMemoryCell> localResults = hybridRetriever.retrieve(
                    effectiveQuery.withTopK(Math.max(query.topK(), query.topK() * 2)),
                    localCandidates);
            if (!localResults.isEmpty()) {
                routes.add(localResults);
            }

            List<ScoredMemoryCell> merged;
            if (routes.isEmpty()) {
                merged = List.of();
            } else if (retrievalProperties.isHybridEnabled() && routes.size() > 1) {
                merged = hybridRetriever.fuseRoutes(routes, query.topK(), query.minScore());
            } else {
                merged = routes.get(0).stream().limit(query.topK()).toList();
            }

            List<ScoredMemoryCell> results = merged.stream()
                    .filter(result -> isEligibleMemory(result.cell()))
                    .map(this::applyMemoryQualityWeight)
                    .filter(result -> result.score() >= query.minScore())
                    .sorted(Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                    .limit(query.topK())
                    .toList();
            Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startMs);
            return QueryResult.of(results, results.size(), elapsed);
        } finally {
            // [全链路追踪] 记忆读取环节埋点
            recordStepTrace(ctx, "MEMORY_READ",
                    "query='" + (query.textQuery() != null && query.textQuery().length() > 120
                            ? query.textQuery().substring(0, 120) + "…" : query.textQuery()) + "'",
                    System.currentTimeMillis() - startMs);
        }
    }

    @Override
    public List<MemoryCell> readTimeline(MemoryId id, int limit) {
        return episodicStore.readTimeline(id, limit);
    }

    // ==================== Lifecycle ====================

    @Override
    public void discard(MemoryId id) {
        workingMemoryManager.discard(id);
        episodicStore.deleteAll(id);
        memoryIdRegistry.remove(id);
        audit(com.yin.ai.platform.memory.audit.MemoryAuditLog.AuditAction.DELETE, id, 0, null);
    }

    @Override
    public boolean updateCell(MemoryId id, MemoryCellId cellId, String content,
                              MemoryContext ctx) {
        if (id == null || cellId == null || content == null) {
            return false;
        }
        // Cell 不存在则不更新（避免 casWrite 把新内容当作插入）
        boolean exists = episodicStore.readAll(id).stream()
                .anyMatch(c -> c.cellId().equals(cellId));
        if (!exists) {
            return false;
        }
        try {
            MemoryCell updated = casAppend(id, cellId, content, ctx);
            // Write-through: ES 按 ownerKey_cellId 幂等 upsert 同一文档
            if (esSearchService != null) {
                esSearchService.indexCell(id, updated);
            }
            audit(com.yin.ai.platform.memory.audit.MemoryAuditLog.AuditAction.UPDATE,
                    id, 1, ctx != null ? ctx.traceId() : null);
            return true;
        } catch (MemoryConcurrencyException e) {
            log.warn("[MEM] updateCell CAS conflict id={}, cellId={}: {}", id, cellId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean discardCell(MemoryId id, MemoryCellId cellId) {
        if (id == null || cellId == null) {
            return false;
        }
        boolean deleted = episodicStore.deleteCell(id, cellId);
        if (deleted && esSearchService != null) {
            esSearchService.deleteCell(id, cellId);
        }
        if (deleted && episodicStore.size(id) == 0) {
            memoryIdRegistry.remove(id);
        }
        return deleted;
    }

    @Override
    public MemorySnapshot snapshot(MemoryId id) {
        List<MemoryCell> cells = episodicStore.readAll(id);
        Instant start = null;
        Instant end = null;
        if (!cells.isEmpty()) {
            start = cells.get(0).timestamp();
            end = cells.get(cells.size() - 1).timestamp();
        }
        return new MemorySnapshot(id, id.type(), LifecycleState.ACTIVE, cells.size(), 0,
                MemorySpan.of(start, end, cells.size()), Map.of());
    }

    @Override
    public MemoryStats stats() {
        long totalCells = 0;
        for (MemoryId id : memoryIdRegistry) {
            totalCells += episodicStore.size(id);
        }
        long totalMemories = memoryIdRegistry.size();
        return new MemoryStats(totalMemories, totalCells, Map.of(), 0, 0,
                totalMemories > 0 ? (double) totalCells / totalMemories : 0);
    }

    @Override
    public void close() {
        memoryIdRegistry.clear();
    }

    // ==================== internal ====================

    private MemoryCell prepareForStorage(MemoryCell cell) {
        if (cell == null) {
            return null;
        }
        MemoryCell prepared = cell;
        if (!prepared.attributes().containsKey("source")) {
            Object role = prepared.attributes().get("role");
            prepared = prepared.withAttribute("source",
                    "user".equalsIgnoreCase(String.valueOf(role))
                            ? "user_explicit" : "runtime_observation");
        }
        if (!prepared.attributes().containsKey("confidence")) {
            prepared = prepared.withAttribute("confidence",
                    retrievalProperties.getDefaultConfidence());
        }
        if (retrievalProperties.isEmbeddingEnabled() && embeddingService != null
                && !prepared.content().isBlank()) {
            try {
                prepared = prepared.withVector(embeddingService.embed(prepared.content()));
            } catch (Exception e) {
                log.debug("[MEM] embedding failed for cell {}: {}",
                        prepared.cellId(), e.getMessage());
            }
        }
        return prepared;
    }

    private MemoryQuery enrichQueryVector(MemoryQuery query) {
        if (!retrievalProperties.isEmbeddingEnabled() || embeddingService == null
                || query.vectorQuery() != null || query.textQuery() == null
                || query.textQuery().isBlank()) {
            return query;
        }
        try {
            return query.withVectorQuery(embeddingService.embed(query.textQuery()));
        } catch (Exception e) {
            log.debug("[MEM] query embedding failed, keyword/BM25 routes remain active: {}",
                    e.getMessage());
            return query;
        }
    }

    private List<MemoryCell> collectCandidateCells(MemoryQuery query, String ownerKey) {
        int max = Math.max(query.topK(), retrievalProperties.getMaxLocalCandidates());
        List<MemoryCell> candidates = new ArrayList<>();
        for (MemoryId memId : collectAllMemoryIds()) {
            if (ownerKey != null && !ownerKey.equals(memId.ownerKey())) {
                continue;
            }
            if (!query.searchTypes().isEmpty() && !query.searchTypes().contains(memId.type())) {
                continue;
            }
            for (MemoryCell cell : episodicStore.readAll(memId)) {
                if (!withinTimeRange(cell, query) || !matchesLocalFilters(cell, query)) {
                    continue;
                }
                candidates.add(cell);
                if (candidates.size() >= max) {
                    return List.copyOf(candidates);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private boolean withinTimeRange(MemoryCell cell, MemoryQuery query) {
        if (query.timeRange() == null) {
            return true;
        }
        Instant timestamp = cell.timestamp();
        return (query.timeRange().start() == null || !timestamp.isBefore(query.timeRange().start()))
                && (query.timeRange().end() == null || !timestamp.isAfter(query.timeRange().end()));
    }

    private boolean matchesLocalFilters(MemoryCell cell, MemoryQuery query) {
        if (query.filters() == null || query.filters().isEmpty()) {
            return true;
        }
        return query.filters().entrySet().stream().allMatch(entry -> {
            Object actual = cell.attributes().get(entry.getKey());
            return actual != null && String.valueOf(actual).equals(String.valueOf(entry.getValue()));
        });
    }

    private boolean isEligibleMemory(MemoryCell cell) {
        String status = String.valueOf(cell.attributes().getOrDefault("status", "ACTIVE"));
        if ("INVALID".equalsIgnoreCase(status) || "REVOKED".equalsIgnoreCase(status)
                || "SUPERSEDED".equalsIgnoreCase(status)) {
            return false;
        }
        Object validUntil = cell.attributes().get("validUntil");
        if (validUntil != null) {
            try {
                Instant expiry = validUntil instanceof Instant instant
                        ? instant : Instant.parse(validUntil.toString());
                if (expiry.isBefore(Instant.now())) {
                    return false;
                }
            } catch (Exception ignored) {
                // 非法时效元数据不能造成整条召回链路失败，交由质量指标暴露。
            }
        }
        return true;
    }

    private ScoredMemoryCell applyMemoryQualityWeight(ScoredMemoryCell result) {
        Object configured = result.cell().attributes().get("confidence");
        if (configured == null) {
            // 兼容旧索引：没有质量元数据时保留原始检索分，不把缺失误判成低质量。
            return result;
        }
        double confidence = retrievalProperties.getDefaultConfidence();
        if (configured instanceof Number number) {
            confidence = number.doubleValue();
        } else if (configured != null) {
            try {
                confidence = Double.parseDouble(configured.toString());
            } catch (NumberFormatException ignored) {
                // 使用配置默认值。
            }
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        double weightedScore = result.score() * confidence;
        return new ScoredMemoryCell(result.cell(), weightedScore, result.sourceType(),
                (result.explanation() != null ? result.explanation() : "UNKNOWN")
                        + ";confidence=" + confidence);
    }

    @Override
    public List<MemoryId> allMemoryIds() {
        return List.copyOf(memoryIdRegistry);
    }

    private List<MemoryId> collectAllMemoryIds() {
        return List.copyOf(memoryIdRegistry);
    }
}
