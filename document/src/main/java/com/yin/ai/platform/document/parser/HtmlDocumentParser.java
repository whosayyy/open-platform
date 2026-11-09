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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * HTML 文档解析器 — 基于 Jsoup 实现，无 LangChain4j 依赖。
 */
public class HtmlDocumentParser implements DocumentParser {

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        return typeInfo.getDocumentType() == DocumentType.HTML;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.HTML);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try {
            //1.字节流转DOM树
            String html = new String(input.getRawBytes(), StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html);

            //2.数据清洗
            for (String tag : new String[]{"script", "style", "nav", "footer", "header"}) {
                Elements elements = doc.select(tag);
                for (Element el : elements) {
                    el.remove();
                }
            }

            //3.提取标题
            String title = doc.title();
            if (title == null || title.isBlank()) {
                //降级查找 Open Graph 协议的 <meta property="og:title">
                Element ogTitle = doc.selectFirst("meta[property=og:title]");
                if (ogTitle != null) {
                    title = ogTitle.attr("content");
                }
            }
            if (title == null || title.isBlank()) {
                //降级使用文件名
                title = input.getFileName();
            }

            //4.提取正文和元数据
            String content = doc.body() != null ? doc.body().text() : doc.text();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());

            String author = extractMeta(doc, "author");
            String description = extractMeta(doc, "description");
            String keywords = extractMeta(doc, "keywords");
            String date = extractMeta(doc, "date", "article:published_time", "pubdate");

            if (description != null) {
                metadata.put("description", description);
            }
            if (keywords != null) {
                metadata.put("keywords", keywords);
            }

            Element ogDesc = doc.selectFirst("meta[property=og:description]");
            if (ogDesc != null) {
                metadata.put("ogDescription", ogDesc.attr("content"));
            }
            Element ogImage = doc.selectFirst("meta[property=og:image]");
            if (ogImage != null) {
                metadata.put("ogImage", ogImage.attr("content"));
            }

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.HTML)
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
                    "Failed to parse HTML: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try {
            // 1.将原始将原始字节流按 UTF-8 解码为 HTML 字符串
            String html = new String(input.getRawBytes(), StandardCharsets.UTF_8);
            // 2.使用 Jsoup 解析 HTML 字符串，得到 Document 对象（DOM 树）
            Document doc = Jsoup.parse(html);

            // 3.清洗：移除无关标签（脚本、样式、导航、页脚、页头）
            //    这些标签对文档正文内容没有意义，属于"噪音"
            for (String tag : new String[]{"script", "style", "nav", "footer", "header"}) {
                for (Element el : doc.select(tag)) {
                    el.remove();
                }
            }

            RootNode root = new RootNode();
            root.setStartOffset(0);
            root.setEndOffset(html.length());

            // 4.提取文档标题，按优先级依次尝试：
            //    优先级1: <title> 标签
            String title = doc.title();
            if (title == null || title.isBlank()) {
                // 优先级2: Open Graph 的 meta 标签 <meta property="og:title" content="...">
                Element ogTitle = doc.selectFirst("meta[property=og:title]");
                if (ogTitle != null) title = ogTitle.attr("content");
            }
            // 优先级3: 兜底使用文件名
            if (title == null || title.isBlank()) title = input.getFileName();
            root.setTitle(title);

            // 5.提取作者信息（从 meta 标签中）
            String author = extractMeta(doc, "author");
            if (author != null) root.setAuthor(author);

            // 6.遍历 <body> 的直接子元素，递归转换为 AST 节点
            if (doc.body() != null) {
                for (Element child : doc.body().children()) {
                    //递归转换
                    DocumentNode node = convertElementToAST(child);
                    if (node != null) {
                        root.addChild(node);
                    }
                }
            }

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (Exception e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse HTML AST: " + e.getMessage(), e);
        }
    }

    private DocumentNode convertElementToAST(Element el) {
        String tag = el.tagName();
        // switch 表达式，根据标签名匹配不同分支，每个分支 yield 一个 AST 节点
        return switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                SectionNode section = new SectionNode();
                section.setHeading(el.text()); // 设置标题文本
                section.setLevel(Integer.parseInt(tag.substring(1)));// 提取层级数字，如 "h2" → 2
                // 递归处理标题内部的子元素（如 <h2><a href="...">链接</a></h2>）
                for (Element child : el.children()) {
                    DocumentNode childNode = convertElementToAST(child);
                    if (childNode != null) section.addChild(childNode);
                }
                // 兜底：如果标题内没有可识别的子节点，就用纯文本创建一个段落节点
                if (section.getChildren().isEmpty()) {
                    ParagraphNode para = new ParagraphNode();
                    para.setText(el.text());
                    section.addChild(para);
                }
                // 返回 SectionNode
                yield section;
            }
            // 段落节点 (<p>) → ParagraphNode
            case "p" -> {
                ParagraphNode para = new ParagraphNode();
                para.setText(el.text()); // 提取纯文本（Jsoup 会自动去除内部 HTML 标签）
                yield para;
            }
            //  列表节点 (<ul> / <ol>) → ListNode
            case "ul", "ol" -> {
                ListNode list = new ListNode();
                list.setOrdered("ol".equals(tag));  // <ol> 是有序列表，<ul> 是无序列表
                int idx = 0;
                for (Element li : el.children()) {
                    if ("li".equals(li.tagName())) {
                        ListItemNode item = new ListItemNode();
                        item.setIndex(idx++);
                        item.setContent(li.text());
                        list.getItems().add(item);// 加入 items 列表（数据层面）
                        list.addChild(item);
                    }
                }
                yield list;
            }
            //  引用块 (<blockquote>) → QuoteNode
            case "blockquote" -> {
                QuoteNode quote = new QuoteNode();
                // 引用层级，这里固定为 1（不支持嵌套引用）
                quote.setLevel(1);
                // 将引用内容包装为一个段落节点
                ParagraphNode qPara = new ParagraphNode();
                qPara.setText(el.text());
                quote.addChild(qPara);
                yield quote;
            }
            //  代码块 (<pre>) → CodeBlockNode
            case "pre" -> {
                CodeBlockNode code = new CodeBlockNode();
                // 尝试从 <pre><code class="language-xxx"> 中提取代码和语言
                Element codeEl = el.selectFirst("code");
                if (codeEl != null) {
                    String cls = codeEl.className();  // 获取 class 属性值
                    // 解析语言标识，如 class="language-java" → language = "java"
                    if (cls.startsWith("language-")) {
                        code.setLanguage(cls.substring("language-".length()));
                    }
                    code.setCode(codeEl.text());
                } else {
                    // 没有 <code> 子标签，直接用 <pre> 的文本
                    code.setCode(el.text());
                }
                yield code;
            }
            //  表格 (<table>) → TableNode
            case "table" -> {
                TableNode table = new TableNode();
                Element thead = el.selectFirst("thead"); // 表头部分
                Element tbody = el.selectFirst("tbody"); // 表体部分
                // ── 提取表头 ──
                // 策略1: 从 <thead> 中的 <th> 提取
                if (thead != null) {
                    for (Element th : thead.select("th")) {
                        if (table.getHeaders() == null) table.setHeaders(new ArrayList<>());
                        table.getHeaders().add(th.text());
                    }
                }
                // 策略2: 如果没有 <thead>，从第一个 <tr> 中提取 <th> 或 <td>
                if (table.getHeaders() == null || table.getHeaders().isEmpty()) {
                    Element headerRow = el.selectFirst("tr");
                    if (headerRow != null) {
                        table.setHeaders(new ArrayList<>());
                        for (Element th : headerRow.select("th, td")) {
                            table.getHeaders().add(th.text());
                        }
                    }
                }
                // ── 提取表体行 ──
                // 遍历 <tbody>（如果有）或整个 <table> 中的所有 <tr>
                for (Element tr : (tbody != null ? tbody : el).select("tr")) {
                    // 跳过 <thead> 中的行（避免把表头重复当数据行）
                    if (thead != null && tr.parent() == thead) continue;
                    TableRowNode row = new TableRowNode();
                    for (Element td : tr.select("td, th")) {
                        TableCellNode cell = new TableCellNode();
                        cell.setText(td.text());
                        row.getCells().add(cell);
                        row.addChild(cell);
                    }
                    if (!row.getCells().isEmpty()) {
                        table.getRows().add(row);
                        table.addChild(row);
                    }
                }
                yield table;
            }
            default -> null;
        };
    }

    private String extractMeta(Document doc, String... names) {
        for (String name : names) {
            Element meta = doc.selectFirst("meta[name=" + name + "]");
            if (meta != null) {
                String content = meta.attr("content");
                if (!content.isBlank()) {
                    return content;
                }
            }
            Element prop = doc.selectFirst("meta[property=" + name + "]");
            if (prop != null) {
                String content = prop.attr("content");
                if (!content.isBlank()) {
                    return content;
                }
            }
        }
        return null;
    }
}
