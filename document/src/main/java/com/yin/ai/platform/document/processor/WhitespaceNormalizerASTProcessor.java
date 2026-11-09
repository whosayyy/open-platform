package com.yin.ai.platform.document.processor;

import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.DocumentNode;
import com.yin.ai.platform.document.model.ast.NodeType;
import com.yin.ai.platform.document.model.ast.ParagraphNode;

/**
 * AST 空白规范化处理器 — 对 ParagraphNode 文本的空白规范化。
 * <p>
 * 等价于 V1 的 {@code WhitespaceNormalizerDocumentCleaner}，
 * 但操作 AST 节点而非字符串 {@code ParsedDocument.content}。
 */
public class WhitespaceNormalizerASTProcessor implements DocumentProcessor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void process(DocumentAST ast) {
        if (ast == null || ast.getRoot() == null) return;
        processNode(ast.getRoot());
    }

    private void processNode(DocumentNode node) {
        if (node.getNodeType() == NodeType.PARAGRAPH) {
            var para = (ParagraphNode) node;
            String text = para.text();
            if (text != null && !text.isEmpty()) {
                para.setText(normalizeWhitespace(text));
            }
        }
        for (DocumentNode child : node.getChildren()) {
            processNode(child);
        }
    }

    static String normalizeWhitespace(String text) {
        if (text == null || text.isEmpty()) return text;

        // Normalize line endings
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");

        // Collapse excessive blank lines and strip trailing whitespace
        StringBuilder sb = new StringBuilder();
        int blankLines = 0;
        for (String line : normalized.split("\n", -1)) {
            String stripped = line.stripTrailing();
            if (stripped.isEmpty()) {
                blankLines++;
                if (blankLines <= 1) {
                    sb.append('\n');
                }
            } else {
                blankLines = 0;
                sb.append(stripped).append('\n');
            }
        }
        String result = sb.toString().stripTrailing();
        return result.isEmpty() ? result : result + '\n';
    }
}
