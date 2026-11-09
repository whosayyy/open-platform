package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.PageNode;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PDF 文档解析器 — 基于 Apache PDFBox，提取文本并转换为 Markdown 格式。
 * <p>
 * 解析策略：
 * <ol>
 *   <li>逐页提取文本，页面标题前加 "## Page N" 标记</li>
 *   <li>尝试从 PDF 元数据中提取标题和作者</li>
 *   <li>使用带位置感知的 TextStripper，保留基本段落结构</li>
 * </ol>
 */
public class PdfDocumentParser implements DocumentParser {

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        return typeInfo.getDocumentType() == DocumentType.PDF;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.PDF);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (PDDocument pdf = Loader.loadPDF(input.getRawBytes())) {
            PDDocumentInformation info = pdf.getDocumentInformation();
            String title = info.getTitle();
            String author = info.getAuthor();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());
            metadata.put("pageCount", pdf.getNumberOfPages());

            if (info.getSubject() != null) metadata.put("subject", info.getSubject());
            if (info.getKeywords() != null) metadata.put("keywords", info.getKeywords());
            if (info.getCreator() != null) metadata.put("creator", info.getCreator());

            if (title == null || title.isBlank()) {
                title = stripExtension(input.getFileName());
            }

            StringBuilder md = new StringBuilder();
            if (title != null && !title.isBlank()) {
                md.append("# ").append(title).append("\n\n");
            }

            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                if (pdf.getNumberOfPages() > 1) {
                    md.append("## Page ").append(i + 1).append("\n\n");
                }

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                stripper.setSortByPosition(true);
                stripper.setAddMoreFormatting(false);

                String pageText = stripper.getText(pdf);
                if (pageText != null && !pageText.isBlank()) {
                    String[] paragraphs = pageText.split("\n{2,}");
                    for (String para : paragraphs) {
                        String cleaned = para.trim().replaceAll("(?m)^[ \\t]+", "");
                        if (!cleaned.isBlank()) {
                            md.append(cleaned).append("\n\n");
                        }
                    }
                }
            }

            String content = md.toString().stripTrailing();

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.PDF)
                    .mimeType(typeInfo.getMimeType())
                    .title(title)
                    .content(content)
                    .contentLength(content.length())
                    .author(author)
                    .parserName(name())
                    .parserVersion(version())
                    .metadata(metadata)
                    .build();

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (PDDocument pdf = Loader.loadPDF(input.getRawBytes())) {
            PDDocumentInformation info = pdf.getDocumentInformation();

            RootNode root = new RootNode();
            root.setStartOffset(0);
            root.setEndOffset(input.getSize());

            String title = info.getTitle();
            if (title == null || title.isBlank()) {
                title = stripExtension(input.getFileName());
            }
            root.setTitle(title);

            String author = info.getAuthor();
            if (author != null) root.setAuthor(author);

            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                PageNode pageNode = new PageNode();
                pageNode.setPageNumber(i + 1);

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                stripper.setSortByPosition(true);

                String pageText = stripper.getText(pdf);
                if (pageText != null && !pageText.isBlank()) {
                    String[] paragraphs = pageText.split("\n{2,}");
                    for (String para : paragraphs) {
                        String cleaned = para.trim();
                        if (!cleaned.isBlank()) {
                            ParagraphNode paraNode = new ParagraphNode();
                            paraNode.setText(cleaned);
                            pageNode.addChild(paraNode);
                        }
                    }
                }
                root.addChild(pageNode);
            }

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse PDF AST: " + e.getMessage(), e);
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
