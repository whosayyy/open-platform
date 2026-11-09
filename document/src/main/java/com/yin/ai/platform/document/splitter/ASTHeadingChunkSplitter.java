package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.DocumentSegment;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.DocumentNode;
import com.yin.ai.platform.document.model.ast.NodeType;
import com.yin.ai.platform.document.model.ast.SectionNode;
import com.yin.ai.platform.document.model.chunk.DocumentChunk;

import java.util.*;

/**
 * V2.0 AST-based Heading Chunk Splitter — 按 Section 边界切分。
 * <p>
 * 遍历 AST 树，为每个 SectionNode 创建一个 DocumentChunk，
 * 包含该 Section 下所有子节点的文本和引用。
 * Chunk 通过 nodeRefs 引用 AST Node，而非持有独立文本副本。
 */
public class ASTHeadingChunkSplitter implements ChunkSplitter {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(ParsedDocument document) {
        return false;
    }

    @Override
    public List<DocumentSegment> split(ParsedDocument document) {
        return List.of();
    }

    @Override
    public boolean supportsAST(DocumentAST ast) {
        return ast != null && ast.sectionCount() > 0;
    }

    @Override
    public List<DocumentChunk> splitAST(DocumentAST ast) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        // Group top-level nodes: each Section + following non-section nodes → 1 chunk
        // 获取根节点的所有直接子节点
        List<DocumentNode> topLevel = ast.getRoot().getChildren();
        List<DocumentNode> currentGroup = new ArrayList<>();
        SectionNode currentSection = null;

        for (DocumentNode node : topLevel) {
            // 遇到新的标题节点，意味着这个chunk要结束了
            if (node instanceof SectionNode section) {
                // Flush previous group
                if (!currentGroup.isEmpty()) {
                    chunks.add(buildChunk(currentGroup, currentSection, chunkIndex++, ast));
                }
                currentGroup = new ArrayList<>();
                currentSection = section;
                currentGroup.add(section);
            } else if (currentSection != null) {
                // 当前节点不是标题节点，将当前节点归入当前组
                currentGroup.add(node);
            } else {
                // Preamble before first heading
                if (chunks.isEmpty()) {
                    currentGroup.add(node);
                } else {
                    // 追加到上一个chunk中，避免丢失
                    chunks.get(chunks.size() - 1).getNodeRefs().add(node.getId());
                }
            }
        }

        // Flush last group
        if (!currentGroup.isEmpty()) {
            chunks.add(buildChunk(currentGroup, currentSection, chunkIndex++, ast));
        }

        // If no sections, create one chunk for all content
        if (chunks.isEmpty() && !topLevel.isEmpty()) {
            chunks.add(buildChunk(topLevel, null, 0, ast));
        }

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            Map<String, Object> enriched = new HashMap<>(chunk.getChunkMetadata());
            enriched.put("totalChunks", chunks.size());
            enriched.put("splitter", "ast-heading");
            chunk.setChunkMetadata(enriched);
        }

        return chunks;
    }

    private DocumentChunk buildChunk(List<DocumentNode> nodes, SectionNode section,
                                      int index, DocumentAST ast) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(UUID.randomUUID().toString());
        chunk.setDocumentId(ast.getDocumentId());
        chunk.setChunkIndex(index);

        List<String> nodeRefs = new ArrayList<>();
        List<String> headingNodeIds = new ArrayList<>();
        StringBuilder headingPath = new StringBuilder();
        long minOffset = Long.MAX_VALUE;
        long maxOffset = 0;
        StringBuilder content = new StringBuilder();

        // Build heading path from section ancestors
        if (section != null) {
            buildHeadingPath(section, headingPath, headingNodeIds);
        } else {
            headingPath.append(ast.getRoot().getTitle() != null
                    ? ast.getRoot().getTitle() : "Document");
        }

        for (DocumentNode node : nodes) {
            collectNodeRefs(node, nodeRefs);
            content.append(node.text());
            // 取最小开始偏移
            if (node.getStartOffset() < minOffset) {
                minOffset = node.getStartOffset();
            }
            // 取最大结束偏移
            if (node.getEndOffset() > maxOffset) {
                maxOffset = node.getEndOffset();
            }
        }

        chunk.setNodeRefs(nodeRefs);
        chunk.setHeadingPath(headingPath.toString());
        chunk.setHeadingNodeIds(headingNodeIds);
        chunk.setStartOffset(minOffset);
        chunk.setEndOffset(maxOffset);
        chunk.setContent(content.toString());
        chunk.setChunkMetadata(Map.of("sectionCount", nodes.size()));

        return chunk;
    }

    // 向上追溯标题链
    private void buildHeadingPath(SectionNode section, StringBuilder path, List<String> nodeIds) {
        // Walk up to collect heading chain, then reverse
        List<SectionNode> chain = new ArrayList<>();
        DocumentNode current = section;
        while (current instanceof SectionNode sec) {
            // 插入到链表头部，实现自然正序
            chain.add(0, sec);
            // 向父节点移动
            current = current.getParent();
            // 如果父节点是根节点，则退出
            if (current != null && current.getNodeType() == NodeType.ROOT) {
                break;
            }
        }
        for (SectionNode sec : chain) {
            if (!path.isEmpty()) {
                path.append(" > ");
            }
            path.append(sec.getHeading());
            nodeIds.add(sec.getId());
        }
    }

    private void collectNodeRefs(DocumentNode node, List<String> refs) {
        refs.add(node.getId());
        for (DocumentNode child : node.getChildren()) {
            if (!(child instanceof SectionNode)) {
                collectNodeRefs(child, refs);
            }
        }
    }
}
