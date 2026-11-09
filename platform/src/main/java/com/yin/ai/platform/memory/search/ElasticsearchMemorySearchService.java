package com.yin.ai.platform.memory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Elasticsearch 的记忆全文检索服务。
 * <p>
 * 职责：
 * <ol>
 *   <li>在 MemoryCell 追加时同步写入 ES 索引（write-through）</li>
 *   <li>在 retrieve 时使用 ES match 查询（BM25 评分）替代内存遍历</li>
 * </ol>
 * <p>
 * 索引名：{@value #INDEX}，mapping 在 {@link #init()} 中自动创建。
 * 分词策略与 RAG 对齐：ik_max_word 建库（最大召回）+ ik_smart 检索（高精度），
 * 配合中文停用词过滤和 lowercase 归一化。IK 不可用时降级为 standard。
 */
public class ElasticsearchMemorySearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchMemorySearchService.class);

    static final String INDEX = "wkc-memory-cells";

    private final ElasticsearchClient client;

    public ElasticsearchMemorySearchService(ElasticsearchClient client) {
        this.client = client;
    }

    /** 初始化索引 mapping（幂等，已存在则跳过）。IK 不可用时降级 standard。 */
    public void init() {
        try {
            boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
            if (exists) return;

            try {
                client.indices().create(c -> c.index(INDEX).withJson(new StringReader(IK_INDEX_JSON)));
                log.info("[MEM-ES] Index '{}' created with IK analyzer (ik_max_word + ik_smart)", INDEX);
            } catch (Exception ikEx) {
                if (ikEx.getMessage() != null && ikEx.getMessage().contains("ik_max_word")) {
                    log.warn("[MEM-ES] IK plugin not available, falling back to standard analyzer");
                    client.indices().create(c -> c.index(INDEX).withJson(new StringReader(STANDARD_INDEX_JSON)));
                    log.info("[MEM-ES] Index '{}' created with standard analyzer", INDEX);
                } else {
                    throw ikEx;
                }
            }
        } catch (Exception e) {
            log.warn("[MEM-ES] Failed to init index '{}': {}", INDEX, e.getMessage());
        }
    }

    private static final String IK_INDEX_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "analysis": {
              "filter": {
                "cn_stop": { "type": "stop", "stopwords": "_chinese_" }
              },
              "analyzer": {
                "mem_index": {
                  "type": "custom",
                  "tokenizer": "ik_max_word",
                  "filter": ["lowercase", "cn_stop"]
                },
                "mem_search": {
                  "type": "custom",
                  "tokenizer": "ik_smart",
                  "filter": ["lowercase", "cn_stop"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "cellId":         { "type": "keyword" },
              "memoryId":       { "type": "keyword" },
              "memoryType":     { "type": "keyword" },
              "ownerKey":       { "type": "keyword" },
              "sessionId":      { "type": "keyword" },
              "roundNo":        { "type": "long" },
              "content":        { "type": "text", "analyzer": "mem_index", "search_analyzer": "mem_search" },
              "source":         { "type": "keyword" },
              "confidence":     { "type": "double" },
              "validUntil":     { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
              "status":         { "type": "keyword" },
              "timestamp":      { "type": "date", "format": "epoch_millis" },
              "sequenceNumber": { "type": "long" }
            }
          }
        }
        """;

    private static final String STANDARD_INDEX_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "analysis": {
              "filter": {
                "cn_stop": { "type": "stop", "stopwords": "_chinese_" }
              },
              "analyzer": {
                "mem_index": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase", "cn_stop"]
                },
                "mem_search": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase", "cn_stop"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "cellId":         { "type": "keyword" },
              "memoryId":       { "type": "keyword" },
              "memoryType":     { "type": "keyword" },
              "ownerKey":       { "type": "keyword" },
              "sessionId":      { "type": "keyword" },
              "roundNo":        { "type": "long" },
              "content":        { "type": "text", "analyzer": "mem_index", "search_analyzer": "mem_search" },
              "source":         { "type": "keyword" },
              "confidence":     { "type": "double" },
              "validUntil":     { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
              "status":         { "type": "keyword" },
              "timestamp":      { "type": "date", "format": "epoch_millis" },
              "sequenceNumber": { "type": "long" }
            }
          }
        }
        """;

    /**
     * 将一个 MemoryCell 写入 ES 索引（write-through）。
     * 失败时仅 warn 不抛异常，不影响主流程。
     */
    public void indexCell(MemoryId memId, MemoryCell cell) {
        try {
            String docId = memId.ownerKey() + "_" + cell.cellId().id();
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("cellId", cell.cellId().id());
            document.put("memoryId", memId.id());
            document.put("memoryType", memId.type().name());
            document.put("ownerKey", memId.ownerKey());
            putIfPresent(document, "sessionId", attrString(cell, "sessionId"));
            putIfPresent(document, "roundNo", attrLong(cell, "roundNo"));
            document.put("content", cell.content());
            putIfPresent(document, "source", cell.attributes().get("source"));
            putIfPresent(document, "confidence", cell.attributes().get("confidence"));
            putIfPresent(document, "validUntil", cell.attributes().get("validUntil"));
            putIfPresent(document, "status", cell.attributes().get("status"));
            document.put("timestamp", cell.timestamp().toEpochMilli());
            document.put("sequenceNumber", cell.sequenceNumber());
            client.index(i -> i
                    .index(INDEX)
                    .id(docId)
                    .document(document)
            );
        } catch (Exception e) {
            log.warn("[MEM-ES] Failed to index cell {}: {}", cell.cellId(), e.getMessage());
        }
    }

    private static String attrString(MemoryCell cell, String key) {
        Object v = cell.attributes() != null ? cell.attributes().get(key) : null;
        return v != null ? v.toString() : null;
    }

    private static Long attrLong(MemoryCell cell, String key) {
        Object v = cell.attributes() != null ? cell.attributes().get(key) : null;
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try {
                return Long.parseLong(v.toString());
            } catch (NumberFormatException ignored) {
                // not numeric
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            target.put(key, value);
        }
    }

    /** Delete the write-through search document for a single memory cell. */
    public void deleteCell(MemoryId memId, MemoryCellId cellId) {
        if (memId == null || cellId == null) {
            return;
        }
        try {
            client.delete(d -> d.index(INDEX).id(memId.ownerKey() + "_" + cellId.id()));
        } catch (Exception e) {
            log.warn("[MEM-ES] Failed to delete cell {}: {}", cellId, e.getMessage());
        }
    }

    /**
     * 使用 ES match 查询检索记忆。
     * <p>
     * 查询结构：bool { must: match(content), filter: [terms(memoryType), term(ownerKey)] }
     * 返回按 BM25 评分降序的 topK 结果。
     *
     * @param query    记忆查询对象（textQuery + searchTypes + topK）
     * @param ownerKey 用户隔离键（null 表示不限制）
     * @return 带 BM25 评分的 ScoredMemoryCell 列表，ES 不可用时返回空列表
     */
    public List<ScoredMemoryCell> search(MemoryQuery query, String ownerKey) {
        if (query.textQuery() == null || query.textQuery().isBlank()) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = client.search(s -> {
                s.index(INDEX).size(query.topK());

                // 构建 bool 查询
                s.query(q -> q.bool(b -> {
                    // must: match on content (BM25 scoring)
                    b.must(m -> m.match(mt -> mt
                            .field("content")
                            .query(query.textQuery())
                    ));

                    // filter: memoryType
                    if (!query.searchTypes().isEmpty()) {
                        List<String> typeNames = query.searchTypes().stream()
                                .map(MemoryType::name)
                                .toList();
                        b.filter(f -> f.terms(t -> t
                                .field("memoryType")
                                .terms(tv -> tv.value(typeNames.stream()
                                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                        .toList()))
                        ));
                    }

                    // filter: ownerKey (user isolation)
                    if (ownerKey != null && !ownerKey.isBlank()) {
                        b.filter(f -> f.term(t -> t
                                .field("ownerKey")
                                .value(ownerKey)
                        ));
                    }

                    // filter: sessionId（会话内检索——长任务拿回同会话历史轮次）
                    Object sessionFilter = query.filters() != null
                            ? query.filters().get("sessionId") : null;
                    if (sessionFilter != null && !sessionFilter.toString().isBlank()) {
                        b.filter(f -> f.term(t -> t
                                .field("sessionId")
                                .value(sessionFilter.toString())
                        ));
                    }

                    return b;
                }));

                return s;
            }, Map.class);

            List<ScoredMemoryCell> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() == null) continue;

                Map<String, Object> src = hit.source();
                Map<String, Object> attributes = new LinkedHashMap<>();
                copyIfPresent(src, attributes, "sessionId");
                copyIfPresent(src, attributes, "roundNo");
                copyIfPresent(src, attributes, "source");
                copyIfPresent(src, attributes, "confidence");
                copyIfPresent(src, attributes, "validUntil");
                copyIfPresent(src, attributes, "status");
                Instant timestamp = src.get("timestamp") instanceof Number number
                        ? Instant.ofEpochMilli(number.longValue()) : Instant.now();
                long sequence = src.get("sequenceNumber") instanceof Number number
                        ? number.longValue() : 0L;
                MemoryType memoryType = MemoryType.valueOf((String) src.get("memoryType"));
                MemoryCell cell = new MemoryCell(
                        MemoryCellId.of((String) src.get("cellId")), memoryType,
                        (String) src.get("content"), attributes, timestamp, sequence,
                        Optional.empty(), Map.of(), null, 1);

                double score = hit.score() != null ? hit.score() : 0.0;
                results.add(new ScoredMemoryCell(cell, score, memoryType, "ES_BM25"));
            }

            log.debug("[MEM-ES] search '{}' → {} hits (owner={})",
                    query.textQuery(), results.size(), ownerKey);
            return results;

        } catch (Exception e) {
            log.warn("[MEM-ES] search failed, returning empty: {}", e.getMessage());
            return List.of();
        }
    }

    private static void copyIfPresent(Map<String, Object> source,
                                      Map<String, Object> target, String key) {
        if (source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    /** 检查 ES 索引是否可用（用于降级判断）。 */
    public boolean isAvailable() {
        try {
            return client.indices().exists(e -> e.index(INDEX)).value();
        } catch (Exception e) {
            return false;
        }
    }
}
