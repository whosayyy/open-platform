package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.CodeBlockNode;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.DocumentNode;
import com.yin.ai.platform.document.model.ast.ListItemNode;
import com.yin.ai.platform.document.model.ast.ListNode;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.QuoteNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import com.yin.ai.platform.document.model.ast.SectionNode;
import com.yin.ai.platform.document.model.ast.TableCellNode;
import com.yin.ai.platform.document.model.ast.TableNode;
import com.yin.ai.platform.document.model.ast.TableRowNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档解析器 — 纯 Java 实现，无 LangChain4j 依赖。
 * <p>
 * 支持 YAML Frontmatter 提取、标题识别、内容保留。
 */
public class MarkdownDocumentParser implements DocumentParser {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^(\\s*)([-*]|\\d+\\.)\\s+(.+)$");
    private static final Pattern QUOTE_PATTERN = Pattern.compile("^(>+)\\s?(.*)$");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        return typeInfo.getDocumentType() == DocumentType.MARKDOWN;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.MARKDOWN);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try {
            //1.字节流转字符串
            String raw = new String(input.getRawBytes(), StandardCharsets.UTF_8);

            String title = null;
            String content = raw;
            Map<String, Object> metadata = new HashMap<>();
            String author = null;
            String date = null;

            //2.YAML Frontmatter 提取与分离
            Matcher fm = FRONTMATTER_PATTERN.matcher(raw);
            if (fm.find()) {
                //提取元数据文本
                String frontmatter = fm.group(1);
                //提取去除元数据后的纯净正文内容
                content = fm.group(2);
                //解析元数据
                title = extractFrontmatterValue(frontmatter, "title", "name");
                author = extractFrontmatterValue(frontmatter, "author");
                date = extractFrontmatterValue(frontmatter, "date", "created", "create_time");

                for (String line : frontmatter.split("\n")) {
                    String trimmed = line.trim();
                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        String key = trimmed.substring(0, colon).trim();
                        String value = trimmed.substring(colon + 1).trim();
                        if (!value.isEmpty() && !isStructuredField(key)) {
                            metadata.put(key, parseFrontmatterValue(value));
                        }
                    }
                }
            }

            if (title == null || title.isBlank()) {
                Matcher hm = HEADING_PATTERN.matcher(content);
                if (hm.find()) {
                    title = hm.group(2).trim();
                }
            }

            if (title == null || title.isBlank()) {
                title = stripExtension(input.getFileName());
            }

            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.MARKDOWN)
                    .mimeType(typeInfo.getMimeType())
                    .title(title)
                    .content(content)
                    .contentLength(content.length())
                    .author(author)
                    .createTime(date)
                    .parserName(name())
                    .parserVersion(version())
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse Markdown: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try {
            String raw = new String(input.getRawBytes(), StandardCharsets.UTF_8);
            String content = raw;
            RootNode root = new RootNode();

            // Parse frontmatter
            Matcher fm = FRONTMATTER_PATTERN.matcher(raw);
            if (fm.find()) {
                String frontmatter = fm.group(1);
                content = fm.group(2);
                root.setTitle(extractFrontmatterValue(frontmatter, "title", "name"));
                root.setAuthor(extractFrontmatterValue(frontmatter, "author"));
                Map<String, Object> rootMeta = new HashMap<>();
                for (String line : frontmatter.split("\n")) {
                    String trimmed = line.trim();
                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        String key = trimmed.substring(0, colon).trim();
                        String value = trimmed.substring(colon + 1).trim();
                        if (!value.isEmpty() && !isStructuredField(key)) {
                            rootMeta.put(key, parseFrontmatterValue(value));
                        }
                    }
                }
                root.setMetadata(rootMeta);
            }
            root.setStartOffset(0);
            root.setEndOffset(raw.length());

            // Build AST from content
            buildMarkdownAST(content, root);

            if (root.getTitle() == null || root.getTitle().isBlank()) {
                root.setTitle(stripExtension(input.getFileName()));
            }

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (Exception e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse Markdown AST: " + e.getMessage(), e);
        }
    }

    private void buildMarkdownAST(String content, RootNode root) {
        // 统一换行符，将文本中所有不同操作系统/平台的换行符统一转换为标准的 \n（Unix/Linux 风格）
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        List<SectionNode> sectionStack = new ArrayList<>();
        DocumentNode currentParent = root;
        boolean inCodeBlock = false;
        String codeLanguage = null;
        StringBuilder codeBuffer = new StringBuilder();
        int codeLineStart = 0;
        int codeStartOffset = 0;

        // 【列表状态】用于处理有序/无序列表，Deque(双端队列)用作栈，支持多级嵌套
        boolean inList = false;
        Deque<ListNode> listStack = new ArrayDeque<>(); // 列表节点栈
        Deque<Integer> listIndents = new ArrayDeque<>(); // 对应的缩进空格数栈

        // 【引用状态】用于处理 > 开头的引用块
        boolean inQuote = false;
        QuoteNode currentQuote = null;
        StringBuilder quoteBuffer = new StringBuilder();

        StringBuilder paraBuffer = new StringBuilder();
        int paraStartOffset = 0;
        int offset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            //因为我们是按照行来处理的，每行末尾都有一个 \n，所以+1是为了还原这一行在原始字符串中真实占据的长度。
            int lineLen = line.length() + 1;

            // 如果当前行是代码块的开始或结束行
            if (line.stripLeading().startsWith("```")) {
                // 进入代码块
                if (!inCodeBlock) {
                    // 刷新段落缓冲区
                    flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent);
                    inCodeBlock = true;
                    codeStartOffset = offset;
                    codeLanguage = line.stripLeading().substring(3).trim();
                    if (codeLanguage.isEmpty()) {
                        codeLanguage = null;
                    }
                    codeBuffer = new StringBuilder();
                    codeLineStart = i;
                    offset += lineLen;
                    continue;
                } else {
                    inCodeBlock = false;
                    CodeBlockNode codeNode = new CodeBlockNode();
                    codeNode.setLanguage(codeLanguage);
                    codeNode.setCode(codeBuffer.toString().stripTrailing());
                    codeNode.setLineStart(codeLineStart);
                    codeNode.setLineEnd(i);
                    codeNode.setStartOffset(codeStartOffset);
                    codeNode.setEndOffset(offset + lineLen);
                    currentParent.addChild(codeNode);
                    offset += lineLen;
                    continue;
                }
            }

            // 当前行不是代码块
            if (inCodeBlock) {
                codeBuffer.append(line).append('\n');
                offset += lineLen;
                continue;
            }

            // 检查是否是标题行
            Matcher hm = HEADING_PATTERN.matcher(line);
            if (hm.matches()) {
                // 遇到标题，说明之前的段落已经结束，刷新缓冲区
                flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent);
                String heading = hm.group(2).trim();
                int level = hm.group(1).length();

                SectionNode section = new SectionNode();
                section.setHeading(heading);
                section.setLevel(level);
                section.setStartOffset(offset);
                section.setEndOffset(offset + lineLen);

                // 如果当前标题层级 <= 栈顶标题层级，说明之前的子章节结束了，需要不断出栈。
                // 例如：栈中是 [H1, H2, H3]，当前遇到 H2。H3 >= H2，H3出栈；H2 >= H2，H2出栈。栈剩 [H1]。
                while (!sectionStack.isEmpty() && sectionStack.get(sectionStack.size() - 1).getLevel() >= level) {
                    sectionStack.remove(sectionStack.size() - 1);
                }
                // 如果栈是空的，说明是顶级标题，直接挂载到root下
                if (sectionStack.isEmpty()) {
                    root.addChild(section);
                } else {
                    sectionStack.get(sectionStack.size() - 1).addChild(section);
                }
                sectionStack.add(section);
                currentParent = section; // 更新全局父节点，后续的内容（段落、列表等）都会挂在这个标题下
                offset += lineLen;
                continue;
            }

            //  表格检测：判断当前行是否以 '|' 开头且以 '|' 结尾
            if (line.startsWith("|") && line.endsWith("|")) {
                // 遇到表格，说明之前的段落已经结束，先刷新段落缓冲区
                flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent);
                List<String[]> rawRows = new ArrayList<>();
                rawRows.add(parseTableRow(line)); // 解析表头行
                int tableStart = offset; // 记录表格起始偏移量
                i++;
                // 向下 lookahead（前瞻），读取表格的后续行
                while (i < lines.length) {
                    String nextLine = lines[i];
                    // 匹配对齐行（如 |---|:---:|），用 null 作为占位符
                    if (nextLine.matches("^\\|[-: |]+\\|$")) {
                        rawRows.add(null); // alignment row placeholder
                        offset += nextLine.length() + 1;
                        i++;
                    } else if (nextLine.startsWith("|") && nextLine.endsWith("|")) {
                        rawRows.add(parseTableRow(nextLine));
                        offset += nextLine.length() + 1;
                        i++;
                    } else {
                        // 遇到非表格行，跳出循环
                        break;
                    }
                }
                i--; // 回退一行，因为外层 for 循环还会执行 i++

                TableNode tableNode = buildTableNode(rawRows, tableStart, offset);
                if (tableNode != null) {
                    currentParent.addChild(tableNode);
                }
                // 更新全局字符偏移量
                offset += lineLen;
                continue;
            }

            // 列表项检测：使用正则匹配列表项（提取缩进、标记符、文本内容）
            Matcher lm = LIST_ITEM_PATTERN.matcher(line);
            if (lm.matches()) {
                // 遇到列表，结束当前的引用和段落
                flushQuote(quoteBuffer, currentQuote, inQuote, offset);
                inQuote = false;
                currentQuote = null;
                flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent);

                // 获取缩进空格数（用于判断嵌套层级）
                int indent = lm.group(1).length();
                // 获取标记符（如 "-"、"*"、"1."）
                String marker = lm.group(2);
                // 获取列表项文本
                String itemText = lm.group(3);
                // 判断是否为有序列表
                boolean ordered = Character.isDigit(marker.charAt(0));

                // 【核心逻辑 1：处理缩进减少（出栈）】
                // 如果当前行的缩进小于栈顶列表的缩进，说明嵌套结束，需要弹栈
                while (!listIndents.isEmpty() && listIndents.peek() > indent) {
                    if (listStack.peek() != null) {
                        // 设置被弹出列表的结束偏移量
                        listStack.peek().setEndOffset(offset);
                    }
                    listStack.pop();
                    listIndents.pop();
                }
                // 【核心逻辑 2：处理新列表或嵌套列表（入栈）】
                if (listStack.isEmpty()) {
                    // 栈为空：创建一个全新的根列表
                    ListNode newList = new ListNode();
                    newList.setOrdered(ordered);
                    newList.setStartOffset(offset);
                    listStack.push(newList);
                    listIndents.push(indent);
                    currentParent.addChild(newList);
                    inList = true;
                } else if (indent > listIndents.peek()) {
                    // 当前缩进大于栈顶缩进：创建一个嵌套的子列表
                    ListNode nestedList = new ListNode();
                    nestedList.setOrdered(ordered);
                    nestedList.setStartOffset(offset);
                    // 将子列表挂载到父列表的【最后一个列表项】下面
                    ListNode parentList = listStack.peek();
                    if (!parentList.getItems().isEmpty()) {
                        ListItemNode lastItem = parentList.getItems().get(parentList.getItems().size() - 1);
                        lastItem.addChild(nestedList);
                    }
                    listStack.push(nestedList);
                    listIndents.push(indent);
                }
                // 【核心逻辑 3：创建当前列表项】
                ListNode activeList = listStack.peek(); // 获取当前最深层的活跃列表
                ListItemNode item = new ListItemNode();
                item.setIndex(activeList.getItems().size()); // 设置索引
                item.setContent(itemText);
                item.setStartOffset(offset);
                item.setEndOffset(offset + lineLen);
                activeList.getItems().add(item);
                activeList.addChild(item);

                offset += lineLen;
                continue;
            }

            // 引用检测：匹配以 '>' 开头的行
            Matcher qm = QUOTE_PATTERN.matcher(line);
            if (qm.matches()) {
                // 遇到引用，结束当前的段落和列表
                flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent);
                flushList(listStack, listIndents, offset);
                inList = false;

                int quoteLevel = qm.group(1).length();// 获取引用的层级（'>' 的数量，如 ">> " 为 2）
                String quoteContent = qm.group(2);// 获取引用文本内容
                // 如果当前不在引用状态，创建一个新的 QuoteNode
                if (!inQuote) {
                    currentQuote = new QuoteNode();
                    currentQuote.setLevel(quoteLevel);
                    currentQuote.setStartOffset(offset);
                    currentParent.addChild(currentQuote);
                    inQuote = true;
                }
                // 将内容追加到引用缓冲区（多行引用会在这里拼接）
                quoteBuffer.append(quoteContent).append('\n');
                offset += lineLen;
                continue;
            }

            // 1. 列表延续行：如果在列表中，且当前行非空，但不是新的列表项
            //    这用于处理列表项内容换行的情况（软换行）
            if (inList && !line.isBlank()) {
                // 找到当前列表的最后一个 item，将文本追加进去
                ListItemNode lastItem = listStack.peek().getItems().get(listStack.peek().getItems().size() - 1);
                lastItem.setContent(lastItem.getContent() + "\n" + line.trim());
                lastItem.setEndOffset(offset + lineLen);
                offset += lineLen;
                continue;
            }

            // 2. 空行处理：空行是 Markdown 中的“块级分隔符”
            if (line.isBlank()) {
                flushList(listStack, listIndents, offset); // 结束列表
                inList = false;
                flushQuote(quoteBuffer, currentQuote, inQuote, offset); // 结束引用
                inQuote = false;
                currentQuote = null;
                flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent); // 刷新段
                offset += lineLen;
                continue;
            }

            // 3. 普通文本：如果不是列表、不是引用、不是空行
            //    先强制结束之前未闭合的列表和引用状态
            if (inList) {
                flushList(listStack, listIndents, offset);
                inList = false;
            }
            if (inQuote) {
                flushQuote(quoteBuffer, currentQuote, inQuote, offset);
                inQuote = false;
                currentQuote = null;
            }

            // 将普通文本追加到段落缓冲区（连续的非空行会被合并为一个段落）
            if (paraBuffer.isEmpty()) {
                paraStartOffset = offset;
            }
            paraBuffer.append(line).append('\n');
            offset += lineLen;
        }

        // 主循环结束后，刷新所有剩余的缓冲区
        flushList(listStack, listIndents, offset);
        flushQuote(quoteBuffer, currentQuote, inQuote, offset);
        flushParagraphBuffer(paraBuffer, paraStartOffset, currentParent);
    }

    private void flushList(Deque<ListNode> listStack, Deque<Integer> listIndents, int offset) {
        // 遍历弹出所有未闭合的列表，设置它们的结束偏移量
        while (!listStack.isEmpty()) {
            ListNode list = listStack.pollLast();
            if (list != null && list.getEndOffset() == 0) {
                list.setEndOffset(offset);
            }
        }
        listIndents.clear();
    }

    private void flushQuote(StringBuilder buffer, QuoteNode quote, boolean inQuote, int offset) {
        if (!inQuote || quote == null) return;
        // 去除尾部多余空白
        String text = buffer.toString().stripTrailing();
        if (!text.isBlank()) {
            ParagraphNode para = new ParagraphNode();
            para.setText(text);
            para.setStartOffset(quote.getStartOffset());
            para.setEndOffset(offset);
            quote.addChild(para);
        }
        // 设置引用块的结束偏移量
        quote.setEndOffset(offset);
        buffer.setLength(0);
    }

    private void flushParagraphBuffer(StringBuilder buffer, int startOffset, DocumentNode parent) {
        if (buffer.isEmpty()) {
            return;
        }
        // 去除字符串末尾的所有空白字符
        String text = buffer.toString().stripTrailing();
        if (!text.isBlank()) {
            ParagraphNode para = new ParagraphNode();
            para.setText(text);
            para.setStartOffset(startOffset);
            para.setEndOffset(startOffset + text.length());
            parent.addChild(para);
        }
        buffer.setLength(0);
    }

    //解析 Markdown 表格的一行数据，提取出各个单元格的内容并去除多余空格
    private String[] parseTableRow(String line) {
        // 去除字符串首尾的 "|"
        String inner = line.substring(1, line.length() - 1);
        String[] cells = inner.split("\\|", -1);
        String[] trimmed = new String[cells.length];
        for (int j = 0; j < cells.length; j++) {
            trimmed[j] = cells[j].trim();
        }
        return trimmed;
    }

    private TableNode buildTableNode(List<String[]> rawRows, int tableStart, int tableEnd) {
        List<String> headers = new ArrayList<>();
        List<String[]> dataRows = new ArrayList<>();
        List<String> alignments = new ArrayList<>();

        int headerIdx = 0;
        // 提取表头，默认把原始数据的第一行当做表头
        if (!rawRows.isEmpty()) {
            for (String cell : rawRows.get(0)) {
                headers.add(cell);
            }
            headerIdx = 1;
        }

        // 如果下一行的索引存在且为null，说明这是markdown表格的列对齐方式行
        if (rawRows.size() > headerIdx && rawRows.get(headerIdx) == null) {
            headerIdx++;
        }

        for (int r = headerIdx; r < rawRows.size(); r++) {
            if (rawRows.get(r) != null) {
                dataRows.add(rawRows.get(r));
            }
        }

        if (headers.isEmpty() && dataRows.isEmpty()) {
            return null;
        }

        TableNode table = new TableNode();
        table.setHeaders(headers);
        table.setStartOffset(tableStart); // 记录表格在原文的开始位置
        table.setEndOffset(tableEnd); // 记录表格在原文的结束位置

        for (String[] dataRow : dataRows) {
            TableRowNode row = new TableRowNode();
            row.setStartOffset(tableStart);
            row.setEndOffset(tableEnd);
            for (String cellText : dataRow) {
                TableCellNode cell = new TableCellNode();
                cell.setText(cellText); // 填入文本内容
                row.getCells().add(cell); // 把单元格添加到行中
                row.addChild(cell); // 维护父子关系
            }
            table.getRows().add(row); // 把这一行加入表格中
            table.addChild(row);
        }

        return table;
    }

    private String extractFrontmatterValue(String frontmatter, String... keys) {
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.trim();
            for (String key : keys) {
                if (trimmed.startsWith(key + ":") || trimmed.startsWith(key + " :")) {
                    int colon = trimmed.indexOf(':');
                    String value = trimmed.substring(colon + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private boolean isStructuredField(String key) {
        return "title".equals(key) || "name".equals(key)
                || "author".equals(key) || "date".equals(key)
                || "created".equals(key) || "create_time".equals(key);
    }

    private Object parseFrontmatterValue(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            // fall through: value is not a parsable integer
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            //去掉前后中括号，拿到中间内容
            String inner = value.substring(1, value.length() - 1);
            // 按逗号分割，逗号前后允许任意空格，生成不可变 List
            return List.of(inner.split("\\s*,\\s*"));
        }
        return value;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
