package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.DocumentNode;
import com.yin.ai.platform.document.model.ast.ListItemNode;
import com.yin.ai.platform.document.model.ast.ListNode;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import com.yin.ai.platform.document.model.ast.SectionNode;
import com.yin.ai.platform.document.model.ast.TableCellNode;
import com.yin.ai.platform.document.model.ast.TableNode;
import com.yin.ai.platform.document.model.ast.TableRowNode;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DOCX 文档解析器 — 基于 Apache POI，将 Word 文档转换为 Markdown 格式。
 * <p>
 * 转换规则：
 * <ol>
 *   <li>标题样式（Heading1-6）→ # ~ ######（取决于大纲级别 + 字体标记）</li>
 *   <li>普通段落 → 纯文本段落</li>
 *   <li>表格 → Markdown 表格（| col1 | col2 |）</li>
 *   <li>列表（识别项目符号）→ - 开头列表</li>
 *   <li>粗体/斜体 → **粗体** / *斜体*</li>
 * </ol>
 */
public class DocxDocumentParser implements DocumentParser {

    private static final double HEADING_FONT_SIZE_THRESHOLD = 16.0;

    @Override
    public int priority() {
        return 85;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        return typeInfo.getDocumentType() == DocumentType.DOCX;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.DOCX);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(input.getRawBytes()))) {
            String title = extractProperty(docx, "Title");
            String author = extractProperty(docx, "Creator");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());

            if (title == null || title.isBlank()) {
                title = stripExtension(input.getFileName());
            }

            StringBuilder md = new StringBuilder();
            md.append("# ").append(title).append("\n\n");

            List<IBodyElement> elements = docx.getBodyElements();
            for (IBodyElement element : elements) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    String paraMd = convertParagraph((XWPFParagraph) element);
                    if (!paraMd.isBlank()) {
                        md.append(paraMd).append("\n\n");
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    String tableMd = convertTable((XWPFTable) element);
                    if (!tableMd.isBlank()) {
                        md.append(tableMd).append("\n\n");
                    }
                }
            }

            String content = md.toString().stripTrailing();

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.DOCX)
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
                    "Failed to parse DOCX: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(input.getRawBytes()))) {
            RootNode root = new RootNode();
            root.setStartOffset(0);
            root.setEndOffset(input.getSize());

            String title = extractProperty(docx, "Title");
            if (title == null || title.isBlank()) {
                title = stripExtension(input.getFileName());
            }
            root.setTitle(title);

            String author = extractProperty(docx, "Creator");
            if (author != null) root.setAuthor(author);

            List<IBodyElement> elements = docx.getBodyElements();
            for (IBodyElement element : elements) {
                DocumentNode node = convertBodyElement(element);
                if (node != null) {
                    root.addChild(node);
                }
            }

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse DOCX AST: " + e.getMessage(), e);
        }
    }

    private DocumentNode convertBodyElement(IBodyElement element) {
        if (element.getElementType() == BodyElementType.PARAGRAPH) {
            return convertParagraphToNode((XWPFParagraph) element);
        } else if (element.getElementType() == BodyElementType.TABLE) {
            return convertTableToNode((XWPFTable) element);
        }
        return null;
    }

    private DocumentNode convertParagraphToNode(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.isBlank()) {
            return null;
        }

        int headingLevel = detectHeadingLevel(para);
        if (headingLevel > 0) {
            SectionNode section = new SectionNode();
            section.setHeading(text.trim());
            section.setLevel(headingLevel);
            ParagraphNode p = new ParagraphNode();
            p.setText(text.trim());
            section.addChild(p);
            return section;
        }

        if (isListItem(para)) {
            ListNode list = new ListNode();
            list.setOrdered(false);
            ListItemNode item = new ListItemNode();
            item.setIndex(0);
            item.setContent(text.replaceFirst("^[•\\-\\*\\u2022]\\s*", "").trim());
            list.getItems().add(item);
            list.addChild(item);
            return list;
        }

        ParagraphNode pn = new ParagraphNode();
        pn.setText(text.trim());
        return pn;
    }

    // --- Markdown conversion helpers ---

    private String convertParagraph(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.isBlank()) {
            return "";
        }

        int headingLevel = detectHeadingLevel(para);
        if (headingLevel > 0) {
            return "#".repeat(headingLevel) + " " + applyInlineFormatting(para);
        }

        if (isListItem(para)) {
            return "- " + applyInlineFormatting(para)
                    .replaceFirst("^[•\\-\\*\\u2022]\\s*", "");
        }

        return applyInlineFormatting(para);
    }

    private int detectHeadingLevel(XWPFParagraph para) {
        String styleId = para.getStyleID();
        if (styleId != null) {
            String lower = styleId.toLowerCase();
            for (int i = 1; i <= 6; i++) {
                if (lower.contains("heading") && lower.contains(String.valueOf(i))) {
                    return i;
                }
            }
        }
        // 大纲级别兜底：outlineLvl >= 0 表示标题
        CTPPr pPr = para.getCTP().getPPr();
        if (pPr != null && pPr.isSetOutlineLvl()) {
            BigInteger lvl = pPr.getOutlineLvl().getVal();
            if (lvl != null && lvl.intValue() >= 0 && lvl.intValue() <= 5) {
                return lvl.intValue() + 1;
            }
        }

        List<XWPFRun> runs = para.getRuns();
        if (runs != null && !runs.isEmpty()) {
            XWPFRun firstRun = runs.get(0);
            if (firstRun.isBold() && firstRun.getFontSizeAsDouble() >= HEADING_FONT_SIZE_THRESHOLD) {
                return 1;
            }
            if (firstRun.isBold() && firstRun.getFontSizeAsDouble() >= 14.0) {
                return 2;
            }
        }

        return 0;
    }

    private boolean isListItem(XWPFParagraph para) {
        String text = para.getText();
        if (text == null) return false;
        String trimmed = text.stripLeading();
        return trimmed.startsWith("•") || trimmed.startsWith("-")
                || trimmed.startsWith("*") || trimmed.startsWith("•");
    }

    private String applyInlineFormatting(XWPFParagraph para) {
        StringBuilder sb = new StringBuilder();
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) {
            return para.getText() != null ? para.getText().trim() : "";
        }

        boolean lastWasBold = false;
        boolean lastWasItalic = false;

        for (XWPFRun run : runs) {
            String runText = run.text();
            if (runText == null || runText.isEmpty()) {
                continue;
            }

            boolean bold = run.isBold();
            boolean italic = run.isItalic();

            if (bold && !lastWasBold) sb.append("**");
            if (italic && !lastWasItalic) sb.append("*");

            sb.append(runText);

            // Close inline markers at end or when style changes
            if (italic && (lastWasItalic || !sb.isEmpty())) {
                // close at end of loop
            }

            lastWasBold = bold;
            lastWasItalic = italic;
        }

        if (lastWasItalic) sb.append("*");
        if (lastWasBold) sb.append("**");

        return sb.toString().trim();
    }

    private String convertTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            List<XWPFTableCell> cells = row.getTableCells();
            sb.append("| ");
            for (int c = 0; c < cells.size(); c++) {
                sb.append(cells.get(c).getText().replace("\n", " ").trim());
                sb.append(" |");
                if (c < cells.size() - 1) sb.append(" ");
            }
            sb.append("\n");

            if (r == 0) {
                sb.append("|");
                for (int c = 0; c < cells.size(); c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private TableNode convertTableToNode(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return null;

        TableNode tableNode = new TableNode();

        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            TableRowNode rowNode = new TableRowNode();
            for (XWPFTableCell cell : row.getTableCells()) {
                TableCellNode cellNode = new TableCellNode();
                cellNode.setText(cell.getText().trim());
                rowNode.getCells().add(cellNode);
                rowNode.addChild(cellNode);
            }
            if (r == 0) {
                List<String> headers = rowNode.getCells().stream()
                        .map(TableCellNode::getText).toList();
                tableNode.setHeaders(headers);
            }
            tableNode.getRows().add(rowNode);
            tableNode.addChild(rowNode);
        }

        return tableNode;
    }

    private String extractProperty(XWPFDocument docx, String key) {
        var props = docx.getProperties();
        if (props == null) return null;
        var core = props.getCoreProperties();
        if (core == null) return null;
        try {
            if ("Title".equals(key)) {
                String v = core.getTitle();
                return v != null && !v.isBlank() ? v : null;
            }
            if ("Creator".equals(key)) {
                String v = core.getCreator();
                return v != null && !v.isBlank() ? v : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
