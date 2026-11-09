package com.yin.ai.platform.rag;

import com.yin.ai.platform.adapter.rag.config.RAGProperties;
import com.yin.ai.platform.core.rag.document.WkcDocument;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 文档服务 — IK 中文分词索引 + 关键词检索 + 批量索引。
 *
 * <p>为国产化中文新闻场景设计：ik_max_word 建库（最大召回）+ ik_smart 检索（高精度），
 * 配合中文停用词过滤和 lowercase 归一化。索引结构兼容 langchain4j embedding 字段，
 * 同时提供独立的 BM25 关键词检索能力。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.rag.enabled", havingValue = "true", matchIfMissing = false)
public class ElasticsearchDocService {

    private final ElasticsearchClient client;
    private final RAGProperties props;

    public ElasticsearchDocService(ElasticsearchClient client, RAGProperties props) {
        this.client = client;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            if (indexExists()) {
                log.info("[ES-DOC] Index '{}' already exists, skip creation", props.getEsIndex());
                return;
            }
            try {
                createIndexWithIk();
                log.info("[ES-DOC] Index '{}' created with IK analyzer (ik_max_word + ik_smart)",
                        props.getEsIndex());
            } catch (Exception ikEx) {
                if (ikEx.getMessage() != null && ikEx.getMessage().contains("ik_max_word")) {
                    log.warn("[ES-DOC] IK plugin not installed, falling back to standard analyzer. "
                            + "Install IK: elasticsearch-plugin install analysis-ik");
                    createIndexWithStandard();
                    log.info("[ES-DOC] Index '{}' created with standard analyzer (limited Chinese support)",
                            props.getEsIndex());
                } else {
                    throw ikEx;
                }
            }
        } catch (Exception e) {
            log.error("[ES-DOC] Failed to initialize index '{}': {}", props.getEsIndex(), e.getMessage(), e);
        }
    }

    // ==================== 索引管理 ====================

    private void createIndexWithIk() throws IOException {
        client.indices().create(c -> c.index(props.getEsIndex()).withJson(new StringReader(IK_INDEX_JSON)));
    }

    private void createIndexWithStandard() throws IOException {
        client.indices().create(c -> c.index(props.getEsIndex()).withJson(new StringReader(STANDARD_INDEX_JSON)));
    }

    private static final String IK_INDEX_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "analysis": {
              "filter": {
                "cn_stop": {
                  "type": "stop",
                  "stopwords": "_chinese_"
                }
              },
              "analyzer": {
                "ik_index": {
                  "type": "custom",
                  "tokenizer": "ik_max_word",
                  "filter": ["lowercase", "cn_stop"]
                },
                "ik_search": {
                  "type": "custom",
                  "tokenizer": "ik_smart",
                  "filter": ["lowercase", "cn_stop"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "docId":   { "type": "keyword" },
              "title":   { "type": "text", "analyzer": "ik_index", "search_analyzer": "ik_search" },
              "content": { "type": "text", "analyzer": "ik_index", "search_analyzer": "ik_search" },
              "metadata":{ "type": "object" }
            }
          }
        }
        """;

    /** 降级方案：standard 分词器 + 中文停用词 + lowercase，无 IK 时使用。 */
    private static final String STANDARD_INDEX_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "analysis": {
              "filter": {
                "cn_stop": {
                  "type": "stop",
                  "stopwords": "_chinese_"
                }
              },
              "analyzer": {
                "ik_index": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase", "cn_stop"]
                },
                "ik_search": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase", "cn_stop"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "docId":   { "type": "keyword" },
              "title":   { "type": "text", "analyzer": "ik_index", "search_analyzer": "ik_search" },
              "content": { "type": "text", "analyzer": "ik_index", "search_analyzer": "ik_search" },
              "metadata":{ "type": "object" }
            }
          }
        }
        """;

    public boolean indexExists() throws IOException {
        return client.indices().exists(e -> e.index(props.getEsIndex())).value();
    }

    // ==================== 文档索引 ====================

    public void indexDocument(WkcDocument doc) throws IOException {
        client.index(i -> i
                .index(props.getEsIndex())
                .id(doc.docId())
                .document(doc)
                .refresh(Refresh.True));
    }

    public void bulkIndex(List<WkcDocument> docs) throws IOException {
        if (docs == null || docs.isEmpty()) return;

        List<BulkOperation> ops = docs.stream()
                .map(doc -> BulkOperation.of(op -> op
                        .index(idx -> idx
                                .index(props.getEsIndex())
                                .id(doc.docId())
                                .document(doc))))
                .toList();

        BulkResponse response = client.bulk(b -> b
                .operations(ops)
                .refresh(Refresh.True));

        if (response.errors()) {
            response.items().forEach(item -> {
                if (item.error() != null) {
                    log.error("[ES-DOC] Bulk index error doc={}: {}", item.id(), item.error().reason());
                }
            });
        } else {
            log.info("[ES-DOC] Bulk indexed {} documents", docs.size());
        }
    }

    // ==================== 中文检索 ====================

    public List<RetrievedDocument> searchByKeyword(String keyword, int size) throws IOException {
        return searchByKeyword(keyword, size, null);
    }

    /**
     * 带多租户隔离的关键词检索 — 只返回用户自己的 + 公共知识库的文档。
     *
     * <p>namespace 过滤规则：
     * <ul>
     *   <li>{userId}:* — 用户私有知识库</li>
     *   <li>public:* — 公共知识库（所有用户可见）</li>
     *   <li>namespace 为空 — 视为公共（兼容历史数据）</li>
     * </ul>
     *
     * @param userId 用户 ID（null 时不过滤）
     */
    public List<RetrievedDocument> searchByKeyword(String keyword, int size, Long userId) throws IOException {
        SearchResponse<WkcDocument> response;

        if (userId != null) {
            // 多租户隔离：bool.must(全文检索) + bool.filter(namespace 前缀匹配)
            String userPrefix = userId + ":";
            response = client.search(s -> s
                            .index(props.getEsIndex())
                            .query(q -> q
                                    .bool(b -> b
                                            .must(m -> m
                                                    .multiMatch(mm -> mm
                                                            .fields("title^2.0", "content")
                                                            .query(keyword)))
                                            .filter(f -> f
                                                    .bool(nb -> nb
                                                            .should(sh -> sh.prefix(p -> p.field("metadata.namespace").value(userPrefix)))
                                                            .should(sh -> sh.prefix(p -> p.field("metadata.namespace").value("public:")))
                                                            .should(sh -> sh.bool(eb -> eb.mustNot(mn -> mn.exists(ex -> ex.field("metadata.namespace")))))
                                                            .minimumShouldMatch("1")))))
                            .size(size),
                    WkcDocument.class);
        } else {
            // 无租户过滤（兼容旧调用）
            response = client.search(s -> s
                            .index(props.getEsIndex())
                            .query(q -> q
                                    .multiMatch(m -> m
                                            .fields("title^2.0", "content")
                                            .query(keyword)))
                            .size(size),
                    WkcDocument.class);
        }

        List<RetrievedDocument> results = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                WkcDocument doc = hit.source();
                Map<String, String> metadata = new HashMap<>();
                metadata.put("esId", hit.id());
                metadata.put("index", hit.index());
                if (doc.metadata() != null) {
                    doc.metadata().forEach((k, v) -> {
                        if (v != null) metadata.put(k, v.toString());
                    });
                }
                results.add(new RetrievedDocument(
                        doc.docId(), doc.title(), doc.content(),
                        hit.score() != null ? hit.score() : 0.0,
                        "bm25", metadata));
            }
        });
        return results;
    }
}
