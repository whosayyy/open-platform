package com.yin.ai.platform.observe.tracing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Elasticsearch Trace 存储 — 将 Span 持久化到 ES 索引 {@code wkc-trace-spans}。
 * <p>
 * 自动创建索引和 mapping（traceId/keyword、spanId/keyword、startTime/long 等），
 * 数据重启不丢失，支持 Kibana 观测。
 */
@Slf4j
public class ElasticsearchTraceStore implements TraceStore {

    private static final String INDEX = "wkc-trace-spans";

    private final ElasticsearchClient client;

    public ElasticsearchTraceStore(ElasticsearchClient client) {
        this.client = client;
    }

    @PostConstruct
    public void init() {
        try {
            createIndexIfNotExists();
        } catch (IOException e) {
            log.error("[TRACE-ES] Failed to initialize index '{}'", INDEX, e);
        }
    }

    // ==================== TraceStore 实现 ====================

    @Override
    public void store(TraceSpan span) {
        try {
            client.index(i -> i
                    .index(INDEX)
                    .document(span)
            );
        } catch (IOException e) {
            log.error("[TRACE-ES] Failed to index span traceId={}", span.getTraceId(), e);
        }
    }

    @Override
    public List<TraceSpan> getTraceSpans(String traceId) {
        try {
            SearchResponse<TraceSpan> response = client.search(s -> s
                            .index(INDEX)
                            .query(q -> q.term(t -> t
                                    .field("traceId")
                                    .value(traceId)
                            ))
                            .sort(o -> o.field(f -> f
                                    .field("startTime")
                                    .order(SortOrder.Asc)
                            ))
                            .size(1000),
                    TraceSpan.class
            );
            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(s -> s != null)
                    .toList();
        } catch (IOException e) {
            log.error("[TRACE-ES] Failed to query traceId={}", traceId, e);
            return List.of();
        }
    }

    @Override
    public List<TraceSummary> getRecentTraces(int limit) {
        try {
            // Step 1: terms 聚合获取最近 traceId 列表
            SearchResponse<Void> aggResponse = client.search(s -> s
                            .index(INDEX)
                            .size(0)
                            .aggregations("by_trace", a -> a
                                    .terms(t -> t
                                            .field("traceId")
                                            .size(limit * 2) // 多取一些用于排序
                                    )
                                    .aggregations("max_start", a2 -> a2
                                            .max(m -> m.field("startTime"))
                                    )
                            ),
                    Void.class
            );

            var buckets = aggResponse.aggregations()
                    .get("by_trace").sterms().buckets().array();

            // 按 max_start 排序取 top limit
            List<Map.Entry<String, Long>> sorted = buckets.stream()
                    .map(b -> Map.entry(
                            b.key().stringValue(),
                            (long) b.aggregations().get("max_start").max().value()
                    ))
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .toList();

            // Step 2: 对每个 traceId 查询 spans 并构建 summary
            List<TraceSummary> summaries = new ArrayList<>(sorted.size());
            for (var entry : sorted) {
                List<TraceSpan> spans = getTraceSpans(entry.getKey());
                if (!spans.isEmpty()) {
                    summaries.add(TraceSummary.fromSpans(entry.getKey(), spans));
                }
            }
            return summaries;
        } catch (IOException e) {
            log.error("[TRACE-ES] Failed to get recent traces", e);
            return List.of();
        }
    }

    @Override
    public List<TraceSummary> getTracesByTimeRange(long startTime, long endTime, int limit) {
        try {
            // 带时间范围过滤的 terms 聚合
            SearchResponse<Void> aggResponse = client.search(s -> s
                            .index(INDEX)
                            .size(0)
                            .query(q -> q.range(r -> r
                                    .number(n -> n
                                            .field("startTime")
                                            .gte((double) startTime)
                                            .lte((double) endTime)
                                    )
                            ))
                            .aggregations("by_trace", a -> a
                                    .terms(t -> t
                                            .field("traceId")
                                            .size(limit * 2)
                                    )
                                    .aggregations("max_start", a2 -> a2
                                            .max(m -> m.field("startTime"))
                                    )
                            ),
                    Void.class
            );

            var buckets = aggResponse.aggregations()
                    .get("by_trace").sterms().buckets().array();

            List<Map.Entry<String, Long>> sorted = buckets.stream()
                    .map(b -> Map.entry(
                            b.key().stringValue(),
                            (long) b.aggregations().get("max_start").max().value()
                    ))
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .toList();

            List<TraceSummary> summaries = new ArrayList<>(sorted.size());
            for (var entry : sorted) {
                List<TraceSpan> spans = getTraceSpans(entry.getKey());
                if (!spans.isEmpty()) {
                    summaries.add(TraceSummary.fromSpans(entry.getKey(), spans));
                }
            }
            return summaries;
        } catch (IOException e) {
            log.error("[TRACE-ES] Failed to get traces by time range [{}, {}]", startTime, endTime, e);
            return List.of();
        }
    }

    @Override
    public int size() {
        try {
            // cardinality 聚合获取近似 distinct trace 数
            SearchResponse<Void> response = client.search(s -> s
                            .index(INDEX)
                            .size(0)
                            .aggregations("distinct_traces", a -> a
                                    .cardinality(c -> c.field("traceId"))
                            ),
                    Void.class
            );
            double value = response.aggregations()
                    .get("distinct_traces").cardinality().value();
            return (int) value;
        } catch (Exception e) {
            log.warn("[TRACE-ES] Failed to count distinct traces", e);
            return 0;
        }
    }

    @Override
    public long totalSpanCount() {
        try {
            var response = client.count(c -> c.index(INDEX));
            return response.count();
        } catch (Exception e) {
            log.warn("[TRACE-ES] Failed to count spans", e);
            return 0;
        }
    }

    @Override
    public void clear() {
        try {
            DeleteByQueryResponse response = client.deleteByQuery(d -> d
                    .index(INDEX)
                    .query(q -> q.matchAll(m -> m))
            );
            log.info("[TRACE-ES] cleared {} documents from '{}'", response.deleted(), INDEX);
        } catch (IOException e) {
            log.error("[TRACE-ES] Failed to clear index '{}'", INDEX, e);
        }
    }

    // ==================== 索引管理 ====================

    private void createIndexIfNotExists() throws IOException {
        boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
        if (!exists) {
            var response = client.indices().create(c -> c
                    .index(INDEX)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                    )
                    .mappings(m -> m
                            .properties("traceId", p -> p.keyword(k -> k))
                            .properties("spanId", p -> p.keyword(k -> k))
                            .properties("parentSpanId", p -> p.keyword(k -> k))
                            .properties("name", p -> p.text(t -> t))
                            .properties("kind", p -> p.keyword(k -> k))
                            .properties("status", p -> p.keyword(k -> k))
                            .properties("startTime", p -> p.long_(l -> l))
                            .properties("endTime", p -> p.long_(l -> l))
                            .properties("durationMs", p -> p.long_(l -> l))
                            .properties("attributes", p -> p.object(o -> o))
                    )
            );
            if (response.acknowledged()) {
                log.info("[TRACE-ES] created index '{}' with mapping", INDEX);
            }
        }
    }
}
