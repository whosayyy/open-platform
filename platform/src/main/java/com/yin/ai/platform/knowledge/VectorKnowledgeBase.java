package com.yin.ai.platform.knowledge;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.knowledge.KnowledgeProperties.KnowledgeBaseConfig;
import com.yin.ai.platform.engine.metrics.AiMetrics;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 向量知识库实现 — embed(query) → ES vector search → rerank → assemble Markdown。
 */
@Slf4j
public class VectorKnowledgeBase implements KnowledgeBase {

    private static final double CHARS_PER_TOKEN = 2.5;

    private final String name;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeBaseConfig config;
    private final int maxContextTokens;
    private final AiMetrics aiMetrics;

    public VectorKnowledgeBase(String name,
                               EmbeddingStore<TextSegment> embeddingStore,
                               EmbeddingModel embeddingModel,
                               KnowledgeBaseConfig config,
                               int maxContextTokens,
                               AiMetrics aiMetrics) {
        this.name = name;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.maxContextTokens = maxContextTokens;
        this.aiMetrics = aiMetrics;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String retrieve(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        long start = System.currentTimeMillis();

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            int candidateCount = config.getMaxDocuments() * config.getCandidateMultiplier();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(candidateCount)
                    .minScore(config.getRerankThreshold())
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            if (matches.isEmpty()) {
                log.debug("[KB:{}] query='{}' documents=0", name, truncateForLog(query));
                return "";
            }

            List<EmbeddingMatch<TextSegment>> topMatches = matches.stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .limit(config.getMaxDocuments())
                    .toList();

            String context = assembleContext(topMatches);

            long duration = System.currentTimeMillis() - start;
            log.info("[KB:{}] query='{}' documents={} durationMs={}",
                    name, truncateForLog(query), topMatches.size(), duration);
            if (aiMetrics != null) {
                aiMetrics.recordRAGRetrieve(duration);
                aiMetrics.recordRAGDocumentsRetrieved(topMatches.size());
            }
            return context;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[KB:{}] query='{}' status=FAIL durationMs={}", name, truncateForLog(query), duration, e);
            return "";
        }
    }

    @Override
    public void index(List<DocumentSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        List<TextSegment> textSegments = segments.stream()
                .map(this::toTextSegment)
                .toList();
        List<Embedding> embeddings = textSegments.stream()
                .map(ts -> embeddingModel.embed(ts.text()).content())
                .toList();
        embeddingStore.addAll(embeddings, textSegments);
        log.info("[KB:{}] indexed {} segments", name, textSegments.size());
    }

    private TextSegment toTextSegment(DocumentSegment segment) {
        Metadata metadata = new Metadata();
        safePut(metadata, "segment_id", segment.getSegmentId());
        safePut(metadata, "segment_type",
                segment.getSegmentType() != null ? segment.getSegmentType().name() : null);
        safePutInt(metadata, "start_offset", segment.getStartOffset());
        safePutInt(metadata, "end_offset", segment.getEndOffset());
        safePutInt(metadata, "heading_level", segment.getHeadingLevel(), 0);
        safePut(metadata, "parent_heading", segment.getParentHeading());
        if (segment.getMetadata() != null) {
            segment.getMetadata().forEach((k, v) -> {
                if (v != null) {
                    metadata.put(k, v.toString());
                }
            });
        }

        // 上下文增强：如果 content 没有 [来源:] 前缀（非 HeadingChunkSplitter 产出），
        // 则从 metadata 中提取 title/parent_heading 构建前缀，提升 Embedding 语义精度
        String text = segment.getContent();
        if (!text.startsWith("[来源:") && segment.getMetadata() != null) {
            String title = strVal(segment.getMetadata().get("title"));
            String parentHeading = segment.getParentHeading();
            if (title != null && !title.isBlank()) {
                StringBuilder prefix = new StringBuilder("[来源: ").append(title);
                if (parentHeading != null && !parentHeading.isBlank()) {
                    prefix.append(" > ").append(parentHeading);
                }
                prefix.append("]\n\n");
                text = prefix + text;
            }
        }

        return TextSegment.from(text, metadata);
    }

    private static String strVal(Object v) {
        return v != null ? v.toString() : null;
    }

    /**
     * 安全的 put String → Metadata，null 值时跳过（LangChain4j 不允许 null value）。
     */
    private static void safePut(Metadata metadata, String key, String value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    /**
     * 安全的 put Integer → Metadata，避免 LangChain4j 的 put(String, int) 重载自动拆箱 NPE。
     */
    private static void safePutInt(Metadata metadata, String key, Integer value) {
        safePutInt(metadata, key, value, 0);
    }

    private static void safePutInt(Metadata metadata, String key, Integer value, int defaultValue) {
        metadata.put(key, value != null ? value : defaultValue);
    }

    private String assembleContext(List<EmbeddingMatch<TextSegment>> matches) {
        int maxChars = (int) (maxContextTokens * CHARS_PER_TOKEN);
        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库: ").append(name).append("\n\n");

        int totalChars = 0;
        int docNum = 1;

        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            String title = segment.metadata().getString("title");
            if (title == null) {
                title = match.embeddingId();
            }

            String content = segment.text();
            String section = String.format("### 文档%d: %s (相关度: %.0f%%)\n%s\n---\n",
                    docNum++, title, match.score() * 100, content);

            int sectionLen = section.length();
            if (totalChars + sectionLen > maxChars) {
                int remaining = maxChars - totalChars;
                if (remaining > 100) {
                    sb.append(section.substring(0, remaining)).append("...\n");
                }
                sb.append("（上下文过长，已截断）");
                break;
            }

            sb.append(section);
            totalChars += sectionLen;
        }

        return sb.toString();
    }

    private String truncateForLog(String query) {
        return query.length() > 50 ? query.substring(0, 50) + "..." : query;
    }
}
