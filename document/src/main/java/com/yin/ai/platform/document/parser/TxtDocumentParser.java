package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TXT 兜底解析器 — 最低优先级，当无其他 Parser 匹配时使用。
 */
public class TxtDocumentParser implements DocumentParser {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        DocumentType dt = typeInfo.getDocumentType();
        return dt == DocumentType.TXT || dt == DocumentType.UNKNOWN;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.TXT, DocumentType.UNKNOWN);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try {
            //1.字节流转字符串，统一换行符
            String raw = new String(input.getRawBytes(), StandardCharsets.UTF_8);
            String body = raw.replace("\r\n", "\n").replace("\r", "\n");

            //2.提取标题
            String title = input.getFileName();
            if (title != null) {
                int dot = title.lastIndexOf('.');
                if (dot > 0) {
                    title = title.substring(0, dot);
                }
            }

            //3.构建 Markdown：标题 + 正文
            StringBuilder md = new StringBuilder();
            md.append("# ").append(title).append("\n\n");
            md.append(body.strip());

            String content = md.toString();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());
            metadata.put("encoding", "UTF-8");

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.TXT)
                    .mimeType(typeInfo.getMimeType())
                    .title(title)
                    .content(content)
                    .contentLength(content.length())
                    .parserName(name())
                    .parserVersion(version())
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse TXT: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try {
            // 1. 字节流转字符串，统一换行符
            String raw = new String(input.getRawBytes(), StandardCharsets.UTF_8);
            String content = raw.replace("\r\n", "\n").replace("\r", "\n");

            // 2. 创建根节点并设置全局边界
            RootNode root = new RootNode();
            root.setStartOffset(0);
            root.setEndOffset(content.length());

            // 3. 提取文档标题（去掉文件后缀）
            String title = input.getFileName();
            if (title != null) {
                int dot = title.lastIndexOf('.');
                if (dot > 0) title = title.substring(0, dot);
            }
            root.setTitle(title);

            // 4. 逐行扫描（卷尺模式），空白行作为段落分隔，offset 随行累加不需要 indexOf 回找
            String[] lines = content.split("\n", -1);
            StringBuilder paraBuffer = new StringBuilder();
            int paraStartOffset = 0;
            int offset = 0;

            for (String line : lines) {
                int lineLen = line.length() + 1; // +1 补偿被 split 吃掉的 \n

                // 空白行 → 段落分隔，刷新缓冲区
                if (line.isBlank()) {
                    flushParaBuffer(paraBuffer, paraStartOffset, offset, root);
                    offset += lineLen;
                    continue;
                }

                // 非空白行 → 记下段落起点，追加到缓冲区
                if (paraBuffer.isEmpty()) {
                    paraStartOffset = offset;
                }
                paraBuffer.append(line).append('\n');
                offset += lineLen;
            }
            // 循环结束后刷新最后一段
            flushParaBuffer(paraBuffer, paraStartOffset, offset, root);

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (Exception e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse TXT AST: " + e.getMessage(), e);
        }
    }

    private void flushParaBuffer(StringBuilder buffer, int startOffset, int endOffset, RootNode root) {
        if (buffer.isEmpty()) return;
        String text = buffer.toString().stripTrailing();
        if (!text.isBlank()) {
            ParagraphNode para = new ParagraphNode();
            para.setText(text);
            para.setStartOffset(startOffset);
            para.setEndOffset(endOffset);
            root.addChild(para);
        }
        buffer.setLength(0);
    }
}
