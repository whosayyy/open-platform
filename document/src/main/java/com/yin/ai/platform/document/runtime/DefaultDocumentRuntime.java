package com.yin.ai.platform.document.runtime;

import com.yin.ai.platform.document.cleaner.DocumentCleaner;
import com.yin.ai.platform.document.detector.DocumentDetector;
import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentRuntimeOptions;
import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.parser.DocumentParser;
import com.yin.ai.platform.document.parser.DocumentParserSelector;
import com.yin.ai.platform.document.processor.DocumentProcessor;
import com.yin.ai.platform.document.source.DocumentSource;
import com.yin.ai.platform.document.splitter.ChunkSplitter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Document Pipeline 编排器 — Fail-soft 原则：任何阶段异常记录 warning 后继续执行。
 * <p>
 * Pipeline 流程：
 * <ol>
 *   <li>source.resolve() → DocumentInput</li>
 *   <li>detector.detect(input) → DocumentTypeInfo</li>
 *   <li>selector.select(input, info) → DocumentParser</li>
 *   <li>parser.parse(input, info) → ParsedDocument</li>
 *   <li>cleaners.forEach(c → c.clean(document))</li>
 *   <li>splitters 中首个 supports() → split(document) → set segments</li>
 * </ol>
 */
@Slf4j
public class DefaultDocumentRuntime implements DocumentRuntime {

    private final DocumentDetector detector;
    private final DocumentParserSelector selector;
    private final List<DocumentCleaner> cleaners;
    private final List<ChunkSplitter> splitters;
    private final List<DocumentProcessor> processors;

    public DefaultDocumentRuntime(DocumentDetector detector,
                                 DocumentParserSelector selector,
                                 List<DocumentCleaner> cleaners,
                                 List<ChunkSplitter> splitters) {
        this(detector, selector, cleaners, splitters, List.of());
    }

    public DefaultDocumentRuntime(DocumentDetector detector,
                                 DocumentParserSelector selector,
                                 List<DocumentCleaner> cleaners,
                                 List<ChunkSplitter> splitters,
                                 List<DocumentProcessor> processors) {
        this.detector = detector;
        this.selector = selector;
        this.cleaners = cleaners.stream()
                .sorted(Comparator.comparingInt(DocumentCleaner::priority))
                .toList();
        this.splitters = splitters.stream()
                .sorted(Comparator.comparingInt(ChunkSplitter::priority).reversed())
                .toList();
        this.processors = processors.stream()
                .sorted(Comparator.comparingInt(DocumentProcessor::order))
                .toList();
    }

    @Override
    public ParsedDocument process(DocumentSource source) {
        return process(source, DocumentRuntimeOptions.builder().build());
    }

    @Override
    public ParsedDocument process(DocumentSource source, DocumentRuntimeOptions options) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        DocumentInput input = null;

        // 1. Resolve source
        try {
            input = source.resolve();
        } catch (Exception e) {
            log.error("[DocumentRuntime] source resolve failed: {}", e.getMessage(), e);
            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.UNKNOWN)
                    .warnings(List.of("Source resolve failed: " + e.getMessage()))
                    .build();
        }

        // 2. Detect
        DocumentTypeInfo typeInfo;
        try {
            typeInfo = detector.detect(input);
        } catch (Exception e) {
            log.warn("[DocumentRuntime] detect failed, using UNKNOWN: {}", e.getMessage());
            warnings.add("Detect failed: " + e.getMessage());
            typeInfo = DocumentTypeInfo.builder()
                    .documentType(DocumentType.UNKNOWN)
                    .mimeType("application/octet-stream")
                    .fileSize(input.getSize())
                    .confidence(0.0)
                    .detectionMethod("error_fallback")
                    .build();
        }

        // 3. Select parser
        Optional<DocumentParser> selectedParser = Optional.empty();
        try {
            selectedParser = selector.select(input, typeInfo);
        } catch (Exception e) {
            log.warn("[DocumentRuntime] selector failed: {}", e.getMessage());
            warnings.add("Parser selection failed: " + e.getMessage());
        }

        // 4. Parse
        ParsedDocument document;
        if (selectedParser.isPresent()) {
            try {
                document = selectedParser.get().parse(input, typeInfo);
            } catch (DocumentParseException e) {
                log.warn("[DocumentRuntime] parse error, using raw content fallback: {}", e.getMessage());
                warnings.add("Parse failed (" + selectedParser.get().name() + "): " + e.getMessage());
                document = rawFallbackDocument(input, typeInfo);
            }
        } else {
            document = rawFallbackDocument(input, typeInfo);
            warnings.add("No parser found for " + typeInfo.getDocumentType());
        }

        // 4.1 AST mode processing
        if (options != null && options.isAstMode() && selectedParser.isPresent() && !processors.isEmpty()) {
            try {
                DocumentAST ast = selectedParser.get().parseToAST(input, typeInfo);
                for (DocumentProcessor processor : processors) {
                    try {
                        processor.process(ast);
                    } catch (Exception e) {
                        log.warn("[DocumentRuntime] AST processor '{}' failed: {}", processor.name(), e.getMessage());
                        warnings.add("AST processor '" + processor.name() + "' failed: " + e.getMessage());
                    }
                }
                // Convert processed AST back to ParsedDocument for downstream compatibility
                document = astToDocument(ast, document);
                // P1-1 修复：AST 存活时立即尝试 AST 拆分（supportsAST/splitAST），
                // 否则 ASTHeadingChunkSplitter 在生产链路永远是死代码
                if (!options.isSkipSplitting()) {
                    List<DocumentSegment> astSegments = splitAST(ast);
                    if (!astSegments.isEmpty()) {
                        document.setSegments(astSegments);
                        log.info("[DocumentRuntime] AST split applied, segments={}", astSegments.size());
                    }
                }
            } catch (Exception e) {
                log.warn("[DocumentRuntime] AST mode failed, using text-mode result: {}", e.getMessage());
                warnings.add("AST mode failed: " + e.getMessage());
            }
        }

        // 5. Clean
        if (options != null && !options.isSkipCleaning()) {
            for (DocumentCleaner cleaner : cleaners) {
                try {
                    if (cleaner.supports(document)) {
                        cleaner.clean(document);
                    }
                } catch (Exception e) {
                    log.warn("[DocumentRuntime] cleaner '{}' failed: {}", cleaner.name(), e.getMessage());
                    warnings.add("Cleaner '" + cleaner.name() + "' failed: " + e.getMessage());
                }
            }
        }

        // 6. Split — 仅当 AST 模式未产出 segments 或非 AST 模式时走 V1 文本拆分
        if (options == null || !options.isSkipSplitting()) {
            if (document.getSegments().isEmpty()) {
                for (ChunkSplitter splitter : splitters) {
                    if (splitter.supports(document)) {
                        try {
                            document.setSegments(splitter.split(document));
                            break;
                        } catch (Exception e) {
                            log.warn("[DocumentRuntime] splitter '{}' failed: {}", splitter.name(), e.getMessage());
                            warnings.add("Splitter '" + splitter.name() + "' failed: " + e.getMessage());
                        }
                    }
                }
            }
        }

        // Accumulate warnings
        List<String> allWarnings = new ArrayList<>(document.getWarnings());
        allWarnings.addAll(warnings);
        document.setWarnings(allWarnings);

        long duration = System.currentTimeMillis() - start;
        log.info("[DocumentRuntime] file='{}' type={} parser={} segments={} warnings={} durationMs={}",
                input.getFileName(), typeInfo.getDocumentType(),
                selectedParser.map(DocumentParser::name).orElse("none"),
                document.getSegments().size(), allWarnings.size(), duration);

        return document;
    }

    /**
     * AST 模式拆分：遍历 splitters 中支持 AST 的实现（supportsAST → splitAST），
     * 将 DocumentChunk 投影转为 DocumentSegment（V1 兼容层）。
     * 无任何 AST splitter 命中或全部失败时返回空列表，调用方回退 V1。
     */
    private List<DocumentSegment> splitAST(DocumentAST ast) {
        for (ChunkSplitter splitter : splitters) {
            if (!splitter.supportsAST(ast)) {
                continue;
            }
            try {
                List<com.yin.ai.platform.document.model.chunk.DocumentChunk> chunks = splitter.splitAST(ast);
                if (chunks == null || chunks.isEmpty()) {
                    continue;
                }
                List<DocumentSegment> segments = new ArrayList<>();
                for (com.yin.ai.platform.document.model.chunk.DocumentChunk chunk : chunks) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    if (chunk.getChunkMetadata() != null) {
                        meta.putAll(chunk.getChunkMetadata());
                    }
                    meta.put("headingPath", chunk.getHeadingPath() != null ? chunk.getHeadingPath() : "");
                    meta.put("chunkIndex", chunk.getChunkIndex());
                    meta.put("splitter", splitter.name());
                    segments.add(DocumentSegment.builder()
                            .segmentId(chunk.getChunkId())
                            .content(chunk.getContent() != null ? chunk.getContent() : "")
                            .segmentType(DocumentSegment.SegmentType.HEADING)
                            .startOffset((int) chunk.getStartOffset())
                            .endOffset((int) chunk.getEndOffset())
                            .metadata(meta)
                            .build());
                }
                if (!segments.isEmpty()) {
                    return segments;
                }
            } catch (Exception e) {
                log.warn("[DocumentRuntime] AST splitter '{}' failed: {}", splitter.name(), e.getMessage());
            }
        }
        return List.of();
    }

    private ParsedDocument astToDocument(DocumentAST ast, ParsedDocument original) {
        String fullText = ast.getRoot().text();
        var builder = ParsedDocument.builder()
                .documentId(ast.getDocumentId())
                .documentType(original.getDocumentType())
                .mimeType(original.getMimeType())
                .title(ast.getRoot().getTitle() != null ? ast.getRoot().getTitle() : original.getTitle())
                .content(fullText)
                .contentLength(fullText.length())
                .parserName(original.getParserName())
                .parserVersion(original.getParserVersion())
                .author(ast.getRoot().getAuthor())
                .metadata(original.getMetadata())
                .segments(original.getSegments())
                .warnings(original.getWarnings());
        return builder.build();
    }

    private ParsedDocument rawFallbackDocument(DocumentInput input, DocumentTypeInfo typeInfo) {
        String content = "";
        try {
            content = new String(input.getRawBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // fall through: best-effort content extraction failed
        }
        return ParsedDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .documentType(typeInfo.getDocumentType())
                .mimeType(typeInfo.getMimeType())
                .title(input.getFileName())
                .content(content)
                .contentLength(content.length())
                .parserName("raw-fallback")
                .parserVersion("1.0.0")
                .build();
    }
}
