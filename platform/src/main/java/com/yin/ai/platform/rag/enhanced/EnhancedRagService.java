package com.yin.ai.platform.rag.enhanced;

import com.yin.ai.platform.core.rag.query.QueryExpansionStrategy;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.yin.ai.platform.core.rag.RetrievedDocument;

/**
 * 企业级 RAG 增强服务 — 编排"问题重写→多路召回→RRF融合→LLM精排→上下文组装"全管道。
 *
 * <p>【迁移自 wkc-ai-service】基座能力归属 platform。
 * 实现 07-RAG.md V2.1/V2.2 规划的企业级 Agentic RAG 能力：
 * <ol>
 *   <li>问题重写（V2.1）：LLM 优化查询为关键词形式，提升召回率</li>
 *   <li>多路召回（V2.1）：向量路（语义）+ BM25 路（关键词）双路并行召回</li>
 *   <li>RRF 融合（V2.1）：按排名融合双路结果，无需分数归一化</li>
 *   <li>LLM 精排（V2.2）：LLM 语义评分，提升 Top-K 精度</li>
 *   <li>上下文组装：拼接结构化 Markdown，截断到 maxContextTokens</li>
 * </ol>
 *
 * <p>各阶段独立可降级：任一阶段失败时跳过该阶段继续执行，不阻塞主流程。
 * LLM 调用（重写/精排）走 AI Gateway（07-RAG.md §15 硬性规则）。
 *
 * <p>注意：userId 由调用方传入（platform 不依赖 service 侧的 GetLoginUserUtil）。
 */
@Slf4j
@Service
public class EnhancedRagService {

    private final QueryRewriter queryRewriter;
    private final VectorRetriever vectorRetriever;
    private final Bm25Retriever bm25Retriever;
    private final LlmReranker llmReranker;
    private final EnhancedRagProperties properties;
    private final ObjectProvider<RetrievalTraceRecorder> traceRecorderProvider;
    private final ObjectProvider<OnlineQualityRecorder> onlineQualityRecorderProvider;
    private final ObjectProvider<com.yin.ai.platform.agent.trace.StepTraceRecorder> stepTraceProvider;
    private List<QueryExpansionStrategy> queryExpansionStrategies = List.of();
    private List<RagDocumentAccessPolicy> documentAccessPolicies = List.of();

    public EnhancedRagService(QueryRewriter queryRewriter,
                              VectorRetriever vectorRetriever,
                              Bm25Retriever bm25Retriever,
                              LlmReranker llmReranker,
                              EnhancedRagProperties properties,
                              ObjectProvider<RetrievalTraceRecorder> traceRecorderProvider,
                              ObjectProvider<OnlineQualityRecorder> onlineQualityRecorderProvider,
                              ObjectProvider<com.yin.ai.platform.agent.trace.StepTraceRecorder> stepTraceProvider) {
        this.queryRewriter = queryRewriter;
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.llmReranker = llmReranker;
        this.properties = properties;
        this.traceRecorderProvider = traceRecorderProvider;
        this.onlineQualityRecorderProvider = onlineQualityRecorderProvider;
        this.stepTraceProvider = stepTraceProvider;
    }

    @Autowired(required = false)
    void setQueryExpansionStrategies(List<QueryExpansionStrategy> strategies) {
        this.queryExpansionStrategies = strategies == null ? List.of() : strategies.stream()
                .sorted(java.util.Comparator.comparingInt(QueryExpansionStrategy::priority))
                .toList();
    }

    @Autowired(required = false)
    void setDocumentAccessPolicies(List<RagDocumentAccessPolicy> policies) {
        this.documentAccessPolicies = policies == null ? List.of() : policies.stream()
                .sorted(java.util.Comparator.comparingInt(RagDocumentAccessPolicy::priority))
                .toList();
    }

    /**
     * 执行企业级增强 RAG 检索，返回文档列表（供测评使用）。
     *
     * @param query 原始用户查询
     * @param userId 用户 ID（用于 Gateway 审计，可为 null）
     */
    public List<RetrievedDocument> retrieveDocuments(String query, Long userId) {
        return retrieveSnapshot(query, userId).documents();
    }

    /**
     * 执行企业级增强 RAG 检索，返回组装好的上下文字符串。
     *
     * @param query 原始用户查询（可能带系统注入的时间上下文前缀，见 {@link #cleanRagQuery}）
     * @param userId 用户 ID（用于 Gateway 审计，可为 null）
     * @return 组装好的 RAG 上下文；无结果时返回空字符串
     */
    public String retrieve(String query, Long userId) {
        return retrieveSnapshot(query, userId).context();
    }

    /**
     * 执行一次完整检索并返回不可变快照。调用方应复用该快照中的 context 和 documents，
     * 不应为了引用展示再次检索。
     */
    public RagRetrievalSnapshot retrieveSnapshot(String query, Long userId) {
        return doRetrieve(query, userId);
    }

    /**
     * 【BUG 修复】RAG 检索 query 净化 — 剥离系统注入到 goal 的时间上下文前缀。
     * <p>
     * 根因：AbstractAgentService.currentTimeContext() 把「【当前时间…】+ 时效性硬性要求 N 条」
     * 拼进 goal，ReActLoop L172 又把整个 goal 当 RAG query 传入 → QueryRewriter 拿到的"原始查询"
     * 是混杂文本（时间块 + 编号要求 + 用户意图），LLM 困惑 → 拒识/超长带标点 → isValidRewrite 拦截
     * （日志 10 次拒识）。
     * <p>
     * 规则（与 CreatorAgentV2Controller.cleanGoalTitle 对齐）：
     * 跳过开头的【…】时间块和「1. 2. 3.」编号指令行，直到遇到用户真实意图行。
     */
    static String cleanRagQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String g = query.trim();
        if (!g.startsWith("【")) {
            return g;
        }
        String[] lines = g.split("\n");
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("【") || l.matches("^\\d+[.、．].*")) {
                start = i + 1;
            } else {
                break;
            }
        }
        if (start > 0 && start < lines.length) {
            return String.join(" ", java.util.Arrays.copyOfRange(lines, start, lines.length)).trim();
        }
        return g;
    }

    private RagRetrievalSnapshot doRetrieve(String query, Long userId) {
        long start = System.currentTimeMillis();
        String traceId = resolveTraceId();
        if (!properties.isEnabled() || query == null || query.isBlank()) {
            return RagRetrievalSnapshot.empty(traceId, query, "rag_disabled_or_blank_query",
                    System.currentTimeMillis() - start);
        }

        // ── Stage 0: query 净化（剥离时间上下文等系统注入前缀，避免 LLM 重写被污染） ──
        String cleanQuery = cleanRagQuery(query);
        if (cleanQuery == null || cleanQuery.isBlank()) {
            return RagRetrievalSnapshot.empty(traceId, query, "blank_query_after_cleanup",
                    System.currentTimeMillis() - start);
        }

        int topK = resolveTopK(cleanQuery);
        int candidateK = topK * Math.max(1, properties.getCandidateMultiplier());

        // ── Stage 1: 问题重写 ──
        String searchQuery = cleanQuery;
        if (properties.getQueryRewrite().isEnabled() && queryRewriter.isAvailable()) {
            searchQuery = queryRewriter.rewrite(cleanQuery, userId);
        }

        java.util.LinkedHashSet<String> expandedVariants = new java.util.LinkedHashSet<>();
        if (properties.getMultiQuery().isEnabled()) {
            expandedVariants.add(cleanQuery);
            if (!searchQuery.equalsIgnoreCase(cleanQuery)) {
                expandedVariants.add(searchQuery);
            }
            int maxQueries = Math.max(1, Math.min(3, properties.getMultiQuery().getMaxQueries()));
            for (QueryExpansionStrategy strategy : queryExpansionStrategies) {
                if (expandedVariants.size() >= maxQueries) break;
                try {
                    for (String expanded : strategy.expand(cleanQuery, userId)) {
                        if (expanded != null && !expanded.isBlank()) expandedVariants.add(expanded.trim());
                        if (expandedVariants.size() >= maxQueries) break;
                    }
                } catch (RuntimeException failure) {
                    log.debug("[QUERY-EXPANSION] strategy={} fallback: {}",
                            strategy.getClass().getSimpleName(), failure.getMessage());
                }
            }
        } else {
            expandedVariants.add(searchQuery);
        }
        List<String> searchVariants = expandedVariants.stream()
                .limit(Math.max(1, Math.min(3, properties.getMultiQuery().getMaxQueries()))).toList();

        // ── Stage 2: 多路召回（带多租户 namespace 过滤） ──
        List<List<RetrievedDocument>> routes = new ArrayList<>();
        int vectorResultCount = 0;
        int bm25ResultCount = 0;
        boolean vectorRouteUsed = false;
        boolean bm25RouteUsed = false;

        for (String variant : searchVariants) {
            List<RetrievedDocument> vectorResults = vectorRetriever.retrieve(
                    variant, candidateK, properties.getMinScore(), userId);
            if (!vectorResults.isEmpty()) {
                routes.add(vectorResults);
                vectorResultCount += vectorResults.size();
                vectorRouteUsed = true;
            }

            if (properties.getBm25().isEnabled()) {
                List<RetrievedDocument> bm25Results = bm25Retriever.retrieve(variant, candidateK, userId);
                if (!bm25Results.isEmpty()) {
                    routes.add(bm25Results);
                    bm25ResultCount += bm25Results.size();
                    bm25RouteUsed = true;
                }
            }
        }

        if (routes.isEmpty()) {
            log.info("[ENHANCED-RAG] query='{}' routes=0 final=0 durationMs={}",
                    query, System.currentTimeMillis() - start);
            return RagRetrievalSnapshot.empty(traceId, query, "no_retrieval_results",
                    System.currentTimeMillis() - start);
        }

        // ── Stage 3: RRF 融合 ──
        RrfFusion fusion = new RrfFusion();
        List<RetrievedDocument> fused = fusion.fuse(routes, candidateK);

        // ── Stage 4: LLM 精排 ──
        List<RetrievedDocument> finalDocs;
        boolean rerankApplied = properties.getRerank().isEnabled()
                && llmReranker.isAvailable() && fused.size() > 1;
        if (rerankApplied) {
            finalDocs = llmReranker.rerank(searchQuery, fused, topK, userId);
        } else {
            finalDocs = fused.stream().limit(topK).toList();
        }

        // A reranker score of 0 used to be displayed as a citation and injected
        // into article generation. Treat semantic rejection as rejection.
        finalDocs = filterLowConfidence(finalDocs, properties.getMinRerankScore());

        // ── Stage 5: 来源去重 — 防止 top-K 全部来自同一文档 ──
        finalDocs = deduplicateBySource(finalDocs, rerankApplied ? finalDocs : fused, topK);

        // ES/vector stores only produce candidates. Authorization is rechecked after reranking,
        // before any content is recorded, cited, or injected into an LLM prompt.
        String tenantId = org.slf4j.MDC.get("tenantId");
        if (!documentAccessPolicies.isEmpty()) {
            finalDocs = finalDocs.stream().filter(document -> documentAccessPolicies.stream()
                    .allMatch(policy -> policy.canRead(document, tenantId, userId))).toList();
        }

        long durationMs = System.currentTimeMillis() - start;
        log.info("[ENHANCED-RAG] query='{}' rewritten='{}' variants={} vector={} bm25={} fused={} final={} durationMs={}",
                query, searchQuery, searchVariants.size(), vectorResultCount,
                bm25ResultCount, fused.size(), finalDocs.size(), durationMs);

        // [全链路追踪] RAG 环节埋点（同 8 位业务 traceId，来自引擎线程 MDC）
        try {
            var stepRecorder = stepTraceProvider.getIfAvailable();
            if (stepRecorder != null) {
                stepRecorder.record(traceId, "RAG_RETRIEVE",
                        "query='" + briefText(query) + "' finalDocs=" + finalDocs.size(),
                        durationMs);
            }
        } catch (Exception ignored) { }

        // ── 溯源记录（异步写入 DB，不阻塞主流程） ──
        try {
            String retrieverType = vectorRouteUsed && bm25RouteUsed
                    ? "HYBRID" : (vectorRouteUsed ? "VECTOR" : "KEYWORD");
            RetrievalTraceRecorder recorder = traceRecorderProvider.getIfAvailable();
            if (recorder != null) {
                recorder.record(
                        traceId,
                        query, "wkc-documents", retrieverType,
                        topK, properties.getMinScore(), durationMs, finalDocs);
            }

            // ── 在线检索质量指标（同 traceId，零 LLM 成本，复用精排分） ──
            OnlineQualityRecorder qualityRecorder = onlineQualityRecorderProvider.getIfAvailable();
            if (qualityRecorder != null) {
                qualityRecorder.recordRagRetrieval(traceId, query, userId,
                        "wkc-documents", retrieverType, topK, finalDocs);
            }
        } catch (Exception e) {
            log.debug("[ENHANCED-RAG] 溯源/指标记录失败（不阻塞）: {}", e.getMessage());
        }

        long independentSources = finalDocs.stream().map(EnhancedRagService::resolveSourceKey)
                .distinct().count();
        boolean sufficient = independentSources >= Math.max(1, properties.getMinEvidenceCount());
        String reason = sufficient ? "" : "insufficient_independent_evidence";
        return new RagRetrievalSnapshot(traceId, query, searchQuery, finalDocs,
                assembleContext(finalDocs), sufficient, reason, durationMs);
    }

    static List<RetrievedDocument> filterLowConfidence(List<RetrievedDocument> docs,
                                                        double minRerankScore) {
        if (docs == null || docs.isEmpty()) return List.of();
        return docs.stream()
                .filter(doc -> !"rerank".equalsIgnoreCase(doc.source())
                        || doc.score() >= minRerankScore)
                .toList();
    }

    /**
     * 来源级去重 — 确保 top-K 结果来自不同源文档，避免同一文档的多个片段垄断结果。
     *
     * <p>策略：按 docId（或 title 兜底）分组，每个来源最多保留 maxChunksPerSource 个片段。
     * 去重后不足 topK 时，从 fused 候选池中按分数顺序补充（跳过已选来源）。
     *
     * @param docs   精排后的 top-K 文档
     * @param fused  RRF 融合后的完整候选池（用于补充）
     * @param topK   目标数量
     */
    private List<RetrievedDocument> deduplicateBySource(List<RetrievedDocument> docs,
                                                         List<RetrievedDocument> fused, int topK) {
        int maxPerSource = properties.getMaxChunksPerSource();
        if (maxPerSource <= 0 || docs.size() <= maxPerSource) {
            return docs; // 无需去重
        }

        // 按来源分组计数
        Map<String, Integer> sourceCount = new java.util.LinkedHashMap<>();
        List<RetrievedDocument> deduped = new ArrayList<>();

        for (RetrievedDocument doc : docs) {
            String sourceKey = resolveSourceKey(doc);
            int count = sourceCount.getOrDefault(sourceKey, 0);
            if (count < maxPerSource) {
                deduped.add(doc);
                sourceCount.put(sourceKey, count + 1);
            }
        }

        // 不足 topK 时从 fused 候选池补充
        if (deduped.size() < topK && fused != null) {
            for (RetrievedDocument candidate : fused) {
                if (deduped.size() >= topK) break;
                String sourceKey = resolveSourceKey(candidate);
                int count = sourceCount.getOrDefault(sourceKey, 0);
                if (count < maxPerSource && !deduped.contains(candidate)) {
                    deduped.add(candidate);
                    sourceCount.put(sourceKey, count + 1);
                }
            }
        }

        if (deduped.size() < docs.size()) {
            log.info("[ENHANCED-RAG] 来源去重: {} → {} (maxChunksPerSource={})",
                    docs.size(), deduped.size(), maxPerSource);
        }
        return deduped;
    }

    /** 解析文档的来源标识 — 优先 docId，兜底 title */
    private static String resolveSourceKey(RetrievedDocument doc) {
        // metadata 中的 source_file 或 fileName 是上传时的原始文件名
        if (doc.metadata() != null) {
            String sourceFile = doc.metadata().get("source_file");
            if (sourceFile == null) sourceFile = doc.metadata().get("fileName");
            if (sourceFile != null && !sourceFile.isBlank()) return sourceFile;
        }
        if (doc.docId() != null && !doc.docId().isBlank()) return doc.docId();
        if (doc.title() != null && !doc.title().isBlank()) return doc.title();
        return "unknown-" + System.identityHashCode(doc);
    }

    private String assembleContext(List<RetrievedDocument> docs) {
        if (docs == null || docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 可引用证据\n\n")
                .append("回答中的事实性结论必须使用对应的 [E编号] 引用；证据未覆盖的内容必须明确说不知道。\n\n");

        int count = 0;
        for (RetrievedDocument doc : docs) {
            sb.append("### [E").append(count + 1).append("]");
            if (doc.title() != null && !doc.title().isBlank()) {
                sb.append(" ").append(doc.title());
            }
            sb.append("\n\n");
            sb.append(doc.content() != null ? doc.content() : "").append("\n\n");
            sb.append("来源标识: ").append(resolveSourceKey(doc)).append("\n\n");
            sb.append("---\n\n");
            count++;
        }

        String context = sb.toString();
        int estimatedTokens = context.length() / 2;
        if (estimatedTokens > properties.getMaxContextTokens()) {
            context = context.substring(0, properties.getMaxContextTokens() * 2)
                    + "\n\n(内容已截断)";
        }
        return context;
    }

    /** 查询越复杂，允许的证据数量越多；简单问答仍保持较小上下文。 */
    int resolveTopK(String query) {
        int base = Math.max(1, properties.getTopK());
        int max = Math.max(base, properties.getMaxTopK());
        if (query == null || query.isBlank()) {
            return base;
        }
        int bonus = 0;
        if (query.length() >= Math.max(1, properties.getComplexQueryLength())) {
            bonus += 2;
        }
        if (query.matches(".*(?:以及|并且|同时|分别|对比|比较|优缺点|原因.*影响).*")) {
            bonus += 2;
        }
        return Math.min(max, base + bonus);
    }

    private static String resolveTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId != null && !traceId.isBlank()
                ? traceId : UUID.randomUUID().toString().replace("-", "");
    }

    public boolean isAvailable() {
        return properties.isEnabled()
                && (vectorRetriever.isAvailable() || bm25Retriever != null);
    }

    private static String briefText(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
