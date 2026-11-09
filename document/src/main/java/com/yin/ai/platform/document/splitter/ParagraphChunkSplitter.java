package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按段落切分 — 以两个及以上连续换行为段落边界。
 * <p>
 * 使用 {@link Matcher} 精确追踪偏移量，超大段落按句子边界切割以保证语义完整性。
 */
public class ParagraphChunkSplitter implements ChunkSplitter {

    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\n\n+");
    private static final String SENTENCE_ENDS = ".。！？!?";

    private final int maxChunkSize;
    private final int chunkOverlap;

    public ParagraphChunkSplitter(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = maxChunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean supports(ParsedDocument document) {
        return document.getContent() != null && !document.getContent().isEmpty();
    }

    @Override
    public List<DocumentSegment> split(ParsedDocument document) {
        String raw = document.getContent();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // Normalize line endings for consistent offset and separator handling
        String content = raw.replace("\r\n", "\n").replace("\r", "\n");

        List<DocumentSegment> segments = new ArrayList<>();
        Matcher m = PARAGRAPH_SEP.matcher(content);

        int paraStart = 0;
        while (m.find()) {
            String rawParagraph = content.substring(paraStart, m.start());
            processParagraph(rawParagraph, paraStart, segments);
            paraStart = m.end();
        }
        // 处理最后一个段落
        if (paraStart < content.length()) {
            processParagraph(content.substring(paraStart), paraStart, segments);
        }

        for (int i = 0; i < segments.size(); i++) {
            DocumentSegment seg = segments.get(i);
            Map<String, Object> enrichedMeta = new HashMap<>();
            enrichedMeta.put("chunkIndex", i);
            enrichedMeta.put("totalChunks", segments.size());
            enrichedMeta.put("splitter", "paragraph");
            seg.setMetadata(enrichedMeta);
        }

        return segments;
    }

    private void processParagraph(String rawParagraph, int paraOffset, List<DocumentSegment> segments) {
        // 手动遍历计算前导/尾随空白，而非直接用 trim()
        int leadWs = 0;
        int len = rawParagraph.length();
        while (leadWs < len && (rawParagraph.charAt(leadWs) == ' ' || rawParagraph.charAt(leadWs) == '\t')) {
            leadWs++;
        }
        int trailWs = 0;
        while (trailWs < len - leadWs && Character.isWhitespace(rawParagraph.charAt(len - 1 - trailWs))) {
            trailWs++;
        }

        String trimmed = rawParagraph.substring(leadWs, len - trailWs);
        if (trimmed.isEmpty()) {
            return;
        }

        int actualStart = paraOffset + leadWs;

        if (trimmed.length() <= maxChunkSize) {
            segments.add(DocumentSegment.builder()
                    .segmentId(UUID.randomUUID().toString())
                    .content(trimmed)
                    .segmentType(DocumentSegment.SegmentType.PARAGRAPH)
                    .startOffset(actualStart)
                    .endOffset(actualStart + trimmed.length())
                    .metadata(Map.of())
                    .build());
        } else {
            splitOversizedParagraph(trimmed, actualStart, segments);
        }
    }

    private void splitOversizedParagraph(String text, int baseOffset, List<DocumentSegment> segments) {
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + maxChunkSize, len);

            //如果end是小于len的，就意味着有可能会截取断完整的句子意思，所以我们要尝试在句子边界截断
            if (end < len) {
                end = findSentenceBoundary(text, start, end);
            }

            String chunk = text.substring(start, end);
            segments.add(DocumentSegment.builder()
                    .segmentId(UUID.randomUUID().toString())
                    .content(chunk)
                    .segmentType(DocumentSegment.SegmentType.PARAGRAPH)
                    .startOffset(baseOffset + start)
                    .endOffset(baseOffset + end)
                    .metadata(Map.of())
                    .build());

            // 如果end已经大于等于len，就意味着已经处理完整段了
            if (end >= len) {
                break;
            }

            // 回退 overlap 长度作为下一块起点
            int next = end - chunkOverlap;
            if (next <= start) {
                // 如果 overlap >= chunkSize，强制前进,当overlap >= chunkSize时
                next = start + maxChunkSize;
            }
            start = next;
        }
    }

    private int findSentenceBoundary(String text, int start, int limit) {
        int window = Math.max(10, maxChunkSize / 5);
        int searchStart = Math.max(start, limit - window);

        // Priority 1: sentence terminator followed by space or at end of search range
        for (int i = limit - 1; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                // 如果遇到换行符，就返回该行末尾的索引+1，跳过当前换行符
                return i + 1;
            }
            if (SENTENCE_ENDS.indexOf(c) >= 0) {
                if (i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                    return i + 2; // 句号+空格 → 跳过空格，避免下一块以空格开头
                }
                return i + 1;
            }
        }

        // Priority 2: any whitespace word boundary
        for (int i = limit - 1; i >= searchStart; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }

        return limit;
    }
}
