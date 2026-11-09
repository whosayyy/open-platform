package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Markdown 标题切分 — 每个 {@code # / ## / ###} 及其下属内容为一个 Segment。
 * <p>
 * 核心优化（V2）：
 * <ul>
 *   <li>上下文前缀注入：每个 chunk 前注入 {@code [来源: 文档标题 > 父标题 > 当前标题]}，
 *       使 Embedding 向量同时编码文档级和章节级语义，显著提升检索精度。</li>
 *   <li>超长章节子切分：当章节正文超过 maxChunkSize 时，按段落/句子边界切割并保留 overlap，
 *       避免长文本稀释向量质量。</li>
 * </ul>
 * <p>
 * 使用 {@link BufferedReader} 流式逐行读取，支持代码块内 {@code #} 跳过、标题层级栈追踪真实父级标题。
 */
public class HeadingChunkSplitter implements ChunkSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern HEADING_PRESENT_PATTERN = Pattern.compile("^#{1,6}\\s+.+", Pattern.MULTILINE);
    private static final Pattern FENCE_PATTERN = Pattern.compile("^\\s{0,3}```");
    private static final String SENTENCE_ENDS = ".。！？!?；;";

    private final int maxChunkSize;
    private final int chunkOverlap;

    public HeadingChunkSplitter() {
        this(1500, 150);
    }

    public HeadingChunkSplitter(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = maxChunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(ParsedDocument document) {
        String content = document.getContent();
        // P1-1b 修复：原实现用 `$` 锚定 + find()，非 MULTILINE 下要求标题在输入末尾，
        // 常见的 `# 标题\n正文` 文档会错误返回 false 落到 ParagraphChunkSplitter。
        // 改用 MULTILINE 模式：任意行首的 Markdown 标题即可命中。
        return content != null && !content.isEmpty() && HEADING_PRESENT_PATTERN.matcher(content).find();
    }

    @Override
    public List<DocumentSegment> split(ParsedDocument document) {
        String raw = document.getContent();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        String content = raw.replace("\r\n", "\n").replace("\r", "\n");
        String docTitle = document.getTitle() != null ? document.getTitle() : "未知文档";

        List<RawSection> sections = parseSections(content, docTitle);
        List<DocumentSegment> segments = new ArrayList<>();

        for (RawSection section : sections) {
            String body = section.body.stripTrailing();
            if (body.isBlank()) {
                continue;
            }

            // 构建上下文前缀：[来源: 文档标题 > 父标题 > 当前标题]
            String contextPrefix = buildContextPrefix(docTitle, section.parentHeading, section.heading);

            if (body.length() <= maxChunkSize) {
                // 短章节：整体作为一个 chunk
                segments.add(buildSegment(
                        contextPrefix + "\n\n" + body,
                        section.level, section.heading, section.parentHeading,
                        section.startOffset, section.endOffset,
                        Map.of("heading", section.heading, "splitter", "heading")));
            } else {
                // 超长章节：按段落/句子边界子切分
                List<String> subChunks = splitOversized(body);
                for (int i = 0; i < subChunks.size(); i++) {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("heading", section.heading);
                    meta.put("splitter", "heading");
                    meta.put("chunkIndex", i);
                    meta.put("totalChunks", subChunks.size());
                    // P2-2 修复：仅首块注入完整 [来源: 文档 > 父 > 当前] 前缀，
                    // 后续子块用轻量前缀（仅当前标题），避免同章节多块重复编码相同前缀浪费向量维度
                    String prefix = i == 0
                            ? contextPrefix
                            : "[续: " + section.heading + "]";
                    segments.add(buildSegment(
                            prefix + "\n\n" + subChunks.get(i),
                            section.level, section.heading, section.parentHeading,
                            section.startOffset, section.endOffset, meta));
                }
            }
        }

        return segments;
    }

    // ==================== 内部方法 ====================

    /**
     * 构建上下文前缀行，让 Embedding 编码文档+章节结构信息。
     */
    private String buildContextPrefix(String docTitle, String parentHeading, String heading) {
        StringBuilder sb = new StringBuilder("[来源: ");
        sb.append(docTitle);
        if (parentHeading != null && !parentHeading.isBlank()) {
            sb.append(" > ").append(parentHeading);
        }
        if (heading != null && !heading.isBlank() && !heading.equals(docTitle)) {
            sb.append(" > ").append(heading);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将超长章节正文按段落边界切割，段落仍超长时按句子边界切割。
     * 段落与段落衔接处保留 overlap（超长段落切分后的尾部 overlap 并入下一段 buffer），
     * 避免跨段边界语义断裂（修复 P1-2）。
     */
    private List<String> splitOversized(String text) {
        List<String> chunks = new ArrayList<>();

        // 先按段落（双换行）切割
        String[] paragraphs = text.split("\n\n+");
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            if (buffer.length() + trimmed.length() + 2 <= maxChunkSize) {
                if (!buffer.isEmpty()) buffer.append("\n\n");
                buffer.append(trimmed);
            } else {
                // 当前 buffer 已满，先 flush
                if (!buffer.isEmpty()) {
                    chunks.add(buffer.toString());
                    // overlap: 保留尾部一段作为下一块开头
                    String overlapTail = getOverlapTail(buffer.toString());
                    buffer = new StringBuilder(overlapTail);
                }
                // 如果单个段落就超长，按句子边界切割
                if (trimmed.length() > maxChunkSize) {
                    List<String> sentenceChunks = splitBySentence(trimmed);
                    chunks.addAll(sentenceChunks);
                    // 关键修复：超长段落切分后，将最后一块的尾部 overlap 保留给下一段，
                    // 而不是清空 buffer —— 保证段落衔接处语义连续
                    if (!sentenceChunks.isEmpty()) {
                        buffer = new StringBuilder(getOverlapTail(sentenceChunks.get(sentenceChunks.size() - 1)));
                    }
                } else {
                    if (!buffer.isEmpty()) buffer.append("\n\n");
                    buffer.append(trimmed);
                }
            }
        }

        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString());
        }

        return chunks.isEmpty() ? List.of(text.substring(0, Math.min(text.length(), maxChunkSize))) : chunks;
    }

    /**
     * 按句子边界切割超长段落。
     */
    private List<String> splitBySentence(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();

        while (start < len) {
            int end = Math.min(start + maxChunkSize, len);
            if (end < len) {
                end = findSentenceBoundary(text, start, end);
            }
            chunks.add(text.substring(start, end).strip());
            if (end >= len) break;
            int next = end - chunkOverlap;
            if (next <= start) next = start + maxChunkSize;
            start = next;
        }
        return chunks;
    }

    private int findSentenceBoundary(String text, int start, int limit) {
        int window = Math.max(10, maxChunkSize / 5);
        int searchStart = Math.max(start, limit - window);

        for (int i = limit - 1; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '\n') return i + 1;
            if (SENTENCE_ENDS.indexOf(c) >= 0) {
                // 句号+空格 → 跳过空格，避免下一块以空格开头（与 ParagraphChunkSplitter 对齐）
                if (i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                    return i + 2;
                }
                return i + 1;
            }
        }
        // 退而求其次：找空白
        for (int i = limit - 1; i >= searchStart; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return limit;
    }

    /**
     * 获取 buffer 尾部 overlap 长度的文本（优先句子边界对齐，避免硬切半截句子）。
     * 修复 P2-3：原实现在无换行时直接从 chunkOverlap 处硬切，可能截断句子。
     */
    private String getOverlapTail(String text) {
        if (text.length() <= chunkOverlap) return text;
        String tail = text.substring(text.length() - chunkOverlap);
        // 优先对齐句子边界（从 overlap 起点往后找句号）
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (SENTENCE_ENDS.indexOf(c) >= 0 && i + 1 < tail.length()) {
                return tail.substring(i + 1);
            }
            if (c == '\n' && i + 1 < tail.length()) {
                return tail.substring(i + 1);
            }
        }
        // 无句子边界：对齐单词空白
        int spaceIdx = tail.indexOf(' ');
        if (spaceIdx > 0 && spaceIdx < tail.length() - 1) {
            return tail.substring(spaceIdx + 1);
        }
        return tail;
    }

    // ==================== 解析 ====================

    private List<RawSection> parseSections(String content, String docTitle) {
        List<RawSection> sections = new ArrayList<>();
        List<HeadingFrame> stack = new ArrayList<>();

        boolean inCodeBlock = false;
        String currentHeading = null;
        int currentLevel = 0;
        String currentParent = null;
        StringBuilder currentContent = new StringBuilder();
        int segmentStart = 0;
        int offset = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int lineLen = line.length() + 1;

                if (FENCE_PATTERN.matcher(line).matches()) {
                    inCodeBlock = !inCodeBlock;
                }

                if (!inCodeBlock) {
                    Matcher hm = HEADING_PATTERN.matcher(line);
                    if (hm.matches()) {
                        // flush 前一个 section
                        if (currentHeading != null && !currentContent.toString().isBlank()) {
                            sections.add(new RawSection(currentHeading, currentLevel,
                                    currentParent, currentContent.toString(), segmentStart, offset));
                        }

                        currentLevel = hm.group(1).length();
                        currentHeading = hm.group(2).trim();

                        while (!stack.isEmpty() && stack.get(stack.size() - 1).level >= currentLevel) {
                            stack.remove(stack.size() - 1);
                        }
                        currentParent = findParent(stack, currentLevel);
                        stack.add(new HeadingFrame(currentHeading, currentLevel));

                        currentContent = new StringBuilder();
                        segmentStart = offset;
                    } else if (currentHeading != null) {
                        currentContent.append(line).append('\n');
                    } else {
                        currentHeading = docTitle;
                        currentLevel = 0;
                        currentParent = null;
                        currentContent.append(line).append('\n');
                        segmentStart = 0;
                    }
                } else {
                    if (currentHeading != null) {
                        currentContent.append(line).append('\n');
                    } else {
                        currentHeading = docTitle;
                        currentLevel = 0;
                        currentParent = null;
                        currentContent.append(line).append('\n');
                        segmentStart = 0;
                    }
                }

                offset += lineLen;
            }
        } catch (IOException e) {
            throw new RuntimeException("Unexpected IO error reading in-memory string", e);
        }

        // flush 最后一个 section
        if (currentHeading != null && !currentContent.toString().isBlank()) {
            sections.add(new RawSection(currentHeading, currentLevel,
                    currentParent, currentContent.toString(), segmentStart, offset));
        }

        return sections;
    }

    private String findParent(List<HeadingFrame> stack, int level) {
        if (level <= 1) {
            return null;
        }
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).level < level) {
                return stack.get(i).title;
            }
        }
        return null;
    }

    private DocumentSegment buildSegment(String content, int level, String heading,
                                         String parentHeading, int start, int end,
                                         Map<String, Object> metadata) {
        return DocumentSegment.builder()
                .segmentId(UUID.randomUUID().toString())
                .content(content)
                .segmentType(level > 0 ? DocumentSegment.SegmentType.HEADING : DocumentSegment.SegmentType.PARAGRAPH)
                .startOffset(start)
                .endOffset(end)
                .headingLevel(level > 0 ? level : null)
                .parentHeading(parentHeading)
                .metadata(metadata)
                .build();
    }

    // ==================== 内部类型 ====================

    private record HeadingFrame(String title, int level) { }

    private record RawSection(String heading, int level, String parentHeading,
                              String body, int startOffset, int endOffset) { }
}
