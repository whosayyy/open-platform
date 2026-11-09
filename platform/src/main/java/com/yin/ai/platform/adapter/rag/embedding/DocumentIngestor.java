package com.yin.ai.platform.adapter.rag.embedding;

import com.yin.ai.platform.adapter.rag.config.RAGProperties;
import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.splitter.ChunkSplitter;
import com.yin.ai.platform.core.rag.document.WkcDocument;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 文档索引管道 — 将 {@link WkcDocument} 向量化后存入 EmbeddingStore。
 * <p>
 * 管道：WkcDocument → ChunkSplitter 拆分 → TextSegment → EmbeddingModel.embed() → EmbeddingStore.add()
 * <p>
 * 【P0-1 修复】原实现把整篇文档作为一个 TextSegment 向量化（无拆分），超大文档被压缩成
 * 单个 embedding 导致检索精度崩坏。现注入 {@link ChunkSplitter} 列表（与 DocumentRuntime
 * 同源装配，按 priority 降序选择首个 supports() 命中的拆分器），逐 chunk 独立向量化。
 * <p>
 * 使用方式：
 * <pre>{@code
 *   ingestor.ingest(new WkcDocument("doc-1", "标题", "正文内容", Map.of()));
 *   ingestor.ingestBatch(List.of(doc1, doc2, doc3));
 * }</pre>
 */
@Slf4j
public class DocumentIngestor {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RAGProperties properties;
    private final List<ChunkSplitter> splitters;

    public DocumentIngestor(EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            RAGProperties properties,
                            List<ChunkSplitter> splitters) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
        this.splitters = splitters.stream()
                .sorted(Comparator.comparingInt(ChunkSplitter::priority).reversed())
                .toList();
    }

    /** 索引单个文档（自动拆分 + 逐 chunk 向量化）。 */
    public void ingest(WkcDocument doc) {
        if (doc == null || doc.content() == null || doc.content().isBlank()) {
            log.debug("[INGESTOR] skipping empty document");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            List<TextSegment> segments = toTextSegments(doc);
            if (segments.isEmpty()) {
                log.warn("[INGESTOR] docId={} no segments after splitting, skip", doc.docId());
                return;
            }
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            for (TextSegment segment : segments) {
                embeddings.add(embeddingModel.embed(segment.text()).content());
            }
            embeddingStore.addAll(embeddings, segments);

            long duration = System.currentTimeMillis() - start;
            log.info("[INGESTOR] docId={} title={} segments={} status=OK durationMs={}",
                    doc.docId(), doc.title(), segments.size(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[INGESTOR] docId={} status=FAIL durationMs={}", doc.docId(), duration, e);
            throw new RuntimeException("Document ingestion failed: " + doc.docId(), e);
        }
    }

    /** 批量索引文档。 */
    public void ingestBatch(List<WkcDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }

        long start = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (WkcDocument doc : docs) {
            try {
                ingest(doc);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("[INGESTOR] batch docId={} failed", doc.docId(), e);
            }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("[INGESTOR] batch complete total={} success={} fail={} durationMs={}",
                docs.size(), successCount, failCount, duration);
    }

    /** 删除指定文档的 Embedding（通过 docId 标记删除）。 */
    public void remove(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        try {
            embeddingStore.remove(docId);
            log.info("[INGESTOR] docId={} status=REMOVED", docId);
        } catch (Exception e) {
            log.error("[INGESTOR] docId={} remove failed", docId, e);
        }
    }

    /**
     * 拆分文档为多个 TextSegment（每 chunk 一个），每个带 docId/title/chunkIndex 元数据。
     * 与 DocumentRuntime 的选型逻辑一致：priority 降序，首个 supports() 命中即用。
     */
    private List<TextSegment> toTextSegments(WkcDocument doc) {
        ParsedDocument parsed = ParsedDocument.builder()
                .documentId(doc.docId())
                .documentType(DocumentType.TXT)
                .title(doc.title())
                .content(doc.content())
                .contentLength(doc.content().length())
                .parserName("wkc-document")
                .build();

        List<DocumentSegment> segments = List.of();
        for (ChunkSplitter splitter : splitters) {
            if (splitter.supports(parsed)) {
                try {
                    segments = splitter.split(parsed);
                    log.debug("[INGESTOR] docId={} splitter={} chunks={}",
                            doc.docId(), splitter.name(), segments.size());
                    break;
                } catch (Exception e) {
                    log.warn("[INGESTOR] docId={} splitter '{}' failed: {}",
                            doc.docId(), splitter.name(), e.getMessage());
                }
            }
        }
        // 无拆分器可用时退化为单块（整篇文档），保证索引不丢
        if (segments.isEmpty()) {
            segments = List.of(DocumentSegment.builder()
                    .segmentId(UUID.randomUUID().toString())
                    .content(doc.content())
                    .segmentType(DocumentSegment.SegmentType.PARAGRAPH)
                    .build());
        }

        List<TextSegment> result = new ArrayList<>(segments.size());
        int chunkIndex = 0;
        for (DocumentSegment seg : segments) {
            Metadata metadata = new Metadata();
            metadata.put("docId", doc.docId());
            metadata.put("title", doc.title());
            metadata.put("chunkIndex", chunkIndex++);
            if (doc.metadata() != null) {
                doc.metadata().forEach((k, v) -> {
                    if (v != null) metadata.put(k, v.toString());
                });
            }
            if (seg.getMetadata() != null) {
                seg.getMetadata().forEach((k, v) -> {
                    if (v != null) metadata.put(k, v.toString());
                });
            }
            result.add(TextSegment.from(seg.getContent(), metadata));
        }
        return result;
    }
}
