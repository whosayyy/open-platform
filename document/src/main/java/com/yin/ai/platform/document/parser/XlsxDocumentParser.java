package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import com.yin.ai.platform.document.model.ast.SectionNode;
import com.yin.ai.platform.document.model.ast.TableCellNode;
import com.yin.ai.platform.document.model.ast.TableNode;
import com.yin.ai.platform.document.model.ast.TableRowNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Excel 表格解析器 — 基于 Apache POI，将 XLSX/XLS 转换为 Markdown 表格格式。
 * <p>
 * 转换规则：
 * <ol>
 *   <li>每个 Sheet → 一个 ## 二级标题 + 一张 Markdown 表格</li>
 *   <li>第一行默认为表头</li>
 *   <li>单元格内容清理换行符，避免破坏表格结构</li>
 * </ol>
 */
public class XlsxDocumentParser implements DocumentParser {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public int priority() {
        return 75;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        return typeInfo.getDocumentType() == DocumentType.XLSX;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.XLSX);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (Workbook wb = createWorkbook(input)) {
            String title = stripExtension(input.getFileName());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());
            metadata.put("sheetCount", wb.getNumberOfSheets());

            StringBuilder md = new StringBuilder();
            md.append("# ").append(title).append("\n\n");

            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                md.append("## ").append(sheetName).append("\n\n");

                String tableMd = sheetToMarkdownTable(sheet);
                if (!tableMd.isBlank()) {
                    md.append(tableMd).append("\n\n");
                }
            }

            String content = md.toString().stripTrailing();

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.XLSX)
                    .mimeType(typeInfo.getMimeType())
                    .title(title)
                    .content(content)
                    .contentLength(content.length())
                    .parserName(name())
                    .parserVersion(version())
                    .metadata(metadata)
                    .build();

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse XLSX: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (Workbook wb = createWorkbook(input)) {
            RootNode root = new RootNode();
            root.setStartOffset(0);
            root.setEndOffset(input.getSize());
            root.setTitle(stripExtension(input.getFileName()));

            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                SectionNode section = new SectionNode();
                section.setHeading(sheet.getSheetName());
                section.setLevel(2);

                TableNode tableNode = sheetToTableNode(sheet);
                if (tableNode != null) {
                    section.addChild(tableNode);
                }

                if (section.getHeading() != null && !section.getHeading().isBlank()) {
                    ParagraphNode headingPara = new ParagraphNode();
                    headingPara.setText(section.getHeading());
                    section.addChild(headingPara);
                }
                root.addChild(section);
            }

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse XLSX AST: " + e.getMessage(), e);
        }
    }

    private Workbook createWorkbook(DocumentInput input) throws IOException {
        String fileName = input.getFileName() != null ? input.getFileName().toLowerCase() : "";
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(input.getRawBytes()));
        } catch (Exception e) {
            if (fileName.endsWith(".xls")) {
                return new HSSFWorkbook(new ByteArrayInputStream(input.getRawBytes()));
            }
            throw e;
        }
    }

    private String sheetToMarkdownTable(Sheet sheet) {
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) return "";

        int maxCols = 0;
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > maxCols) {
                maxCols = row.getLastCellNum();
            }
        }
        if (maxCols == 0) return "";

        StringBuilder sb = new StringBuilder();

        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            sb.append("| ");
            for (int c = 0; c < maxCols; c++) {
                String val = getCellValue(row, c);
                sb.append(val).append(" |");
                if (c < maxCols - 1) sb.append(" ");
            }
            sb.append("\n");

            if (r == 0) {
                sb.append("|");
                for (int c = 0; c < maxCols; c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private TableNode sheetToTableNode(Sheet sheet) {
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) return null;

        int maxCols = 0;
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > maxCols) {
                maxCols = row.getLastCellNum();
            }
        }
        if (maxCols == 0) return null;

        TableNode tableNode = new TableNode();
        List<String> headers = null;

        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            TableRowNode rowNode = new TableRowNode();
            for (int c = 0; c < maxCols; c++) {
                TableCellNode cellNode = new TableCellNode();
                cellNode.setText(getCellValue(row, c));
                rowNode.getCells().add(cellNode);
                rowNode.addChild(cellNode);
            }
            if (r == 0) {
                headers = rowNode.getCells().stream()
                        .map(TableCellNode::getText).toList();
                tableNode.setHeaders(headers);
            }
            tableNode.getRows().add(rowNode);
            tableNode.addChild(rowNode);
        }

        return tableNode;
    }

    private String getCellValue(Row row, int col) {
        if (row == null) return "";
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        try {
            String val = FORMATTER.formatCellValue(cell);
            return val != null ? val.replace("\n", " ").replace("\r", " ").trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
