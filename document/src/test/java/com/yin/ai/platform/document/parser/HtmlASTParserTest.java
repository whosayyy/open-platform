package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HtmlDocumentParser.parseToAST")
class HtmlASTParserTest {

    private HtmlDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new HtmlDocumentParser();
    }

    @Test
    @DisplayName("解析 h1-h2 为 SectionNode")
    void shouldParseHeadingsAsSections() throws DocumentParseException {
        String html = "<html><body><h1>Main</h1><p>Intro</p><h2>Sub</h2><p>More</p></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.sectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("解析 p 为 ParagraphNode")
    void shouldParseParagraphs() throws DocumentParseException {
        String html = "<html><body><p>Paragraph one</p><p>Paragraph two</p></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.findByType(NodeType.PARAGRAPH)).hasSize(2);
    }

    @Test
    @DisplayName("解析 ul 为 ListNode")
    void shouldParseUnorderedList() throws DocumentParseException {
        String html = "<html><body><ul><li>A</li><li>B</li><li>C</li></ul></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.findByType(NodeType.LIST)).hasSize(1);
        assertThat(ast.findByType(NodeType.LIST_ITEM)).hasSize(3);
    }

    @Test
    @DisplayName("解析 pre code 为 CodeBlockNode")
    void shouldParseCodeBlock() throws DocumentParseException {
        String html = "<html><body><pre><code class=\"language-java\">int x = 1;</code></pre></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.findByType(NodeType.CODE_BLOCK)).hasSize(1);
    }

    @Test
    @DisplayName("解析表格为 TableNode")
    void shouldParseTable() throws DocumentParseException {
        String html = "<html><body><table><tr><th>Name</th><th>Age</th></tr>" +
                "<tr><td>Alice</td><td>30</td></tr></table></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.findByType(NodeType.TABLE)).hasSize(1);
    }

    @Test
    @DisplayName("解析 blockquote 为 QuoteNode")
    void shouldParseBlockquote() throws DocumentParseException {
        String html = "<html><body><blockquote><p>Quoted text</p></blockquote></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.findByType(NodeType.QUOTE)).hasSize(1);
    }

    @Test
    @DisplayName("空 HTML 创建空 AST")
    void shouldCreateEmptyAST() throws DocumentParseException {
        String html = "<html><body></body></html>";
        DocumentAST ast = parse(html);
        assertThat(ast.nodeCount()).isGreaterThanOrEqualTo(1); // root
    }

    private DocumentAST parse(String html) throws DocumentParseException {
        DocumentInput input = DocumentInput.builder()
                .rawBytes(html.getBytes(StandardCharsets.UTF_8))
                .fileName("test.html")
                .size(html.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.HTML).mimeType("text/html").build();
        return parser.parseToAST(input, info);
    }
}
