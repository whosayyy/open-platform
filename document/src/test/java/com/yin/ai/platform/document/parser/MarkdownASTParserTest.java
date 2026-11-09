package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ListItemNode;
import com.yin.ai.platform.document.model.ast.ListNode;
import com.yin.ai.platform.document.model.ast.NodeType;
import com.yin.ai.platform.document.model.ast.QuoteNode;
import com.yin.ai.platform.document.model.ast.SectionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownDocumentParser.parseToAST")
class MarkdownASTParserTest {

    private MarkdownDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarkdownDocumentParser();
    }

    @Test
    @DisplayName("解析标题为 SectionNode")
    void shouldParseHeadingAsSection() throws DocumentParseException {
        String md = "## Introduction\n\nContent text.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);

        assertThat(ast.sectionCount()).isEqualTo(1);
        SectionNode section = ast.getSections().get(0);
        System.out.println(section);
        System.out.println(section.getHeading());
        System.out.println(section.getLevel());
        assertThat(section.getHeading()).isEqualTo("Introduction");
        assertThat(section.getLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("标题下段落为 ParagraphNode")
    void shouldParseParagraphUnderHeading() throws DocumentParseException {
        String md = "# Title\n\nParagraph one.\n\nParagraph two.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);

        assertThat(ast.findByType(NodeType.PARAGRAPH)).hasSize(2);
        SectionNode section = ast.getSections().get(0);
        System.out.println(section);
        assertThat(section.getChildren()).hasSize(2);
    }

    @Test
    @DisplayName("解析代码块为 CodeBlockNode")
    void shouldParseCodeBlock() throws DocumentParseException {
        String md = "# Code\n\n```java\nSystem.out.println(\"hi\");\n```\n\nAfter code.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);
        assertThat(ast.findByType(NodeType.CODE_BLOCK)).hasSize(1);
    }

    @Test
    @DisplayName("解析 Markdown 表格为 TableNode")
    void shouldParseTable() throws DocumentParseException {
        String md = "# Data\n\n| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);
        assertThat(ast.tableCount()).isEqualTo(1);
        assertThat(ast.findByType(NodeType.TABLE_ROW)).hasSize(2);
    }

    @Test
    @DisplayName("提取 Frontmatter 中的 title 到 RootNode")
    void shouldExtractFrontmatterTitle() throws DocumentParseException {
        String md = "---\ntitle: MyDoc\nauthor: Alice\n---\n\n# Content\n\nBody.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);
        assertThat(ast.getRoot().getTitle()).isEqualTo("MyDoc");
        assertThat(ast.getRoot().getAuthor()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("空文档创建空 AST")
    void shouldCreateEmptyAST() throws DocumentParseException {
        DocumentInput input = DocumentInput.builder()
                .rawBytes("".getBytes(StandardCharsets.UTF_8))
                .fileName("empty.md")
                .size(0)
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);
        assertThat(ast.nodeCount()).isEqualTo(1); // root only
    }

    @Test
    @DisplayName("同一级别多个标题正确解析")
    void shouldParseMultipleSections() throws DocumentParseException {
        String md = "# A\n\nContent A.\n\n# B\n\nContent B.";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();

        DocumentAST ast = parser.parseToAST(input, info);
        System.out.println(ast);

        assertThat(ast.sectionCount()).isEqualTo(2);
        assertThat(ast.getSections().get(0).getHeading()).isEqualTo("A");
        assertThat(ast.getSections().get(1).getHeading()).isEqualTo("B");
    }

    // ——— List tests ———

    @Test
    @DisplayName("解析无序列表为 ListNode")
    void shouldParseUnorderedList() throws DocumentParseException {
        String md = "# List\n\n- item one\n- item two\n- item three";
        DocumentAST ast = parse(md);
        assertThat(ast.findByType(NodeType.LIST)).hasSize(1);
        ListNode list = (ListNode) ast.findByType(NodeType.LIST).get(0);
        System.out.println(ast);
        System.out.println(list);
        assertThat(list.isOrdered()).isFalse();
        assertThat(list.getItems()).hasSize(3);
        assertThat(list.getItems().get(0).getContent()).isEqualTo("item one");
    }

    @Test
    @DisplayName("解析有序列表为 ListNode")
    void shouldParseOrderedList() throws DocumentParseException {
        String md = "1. first\n2. second\n3. third";
        DocumentAST ast = parse(md);
        assertThat(ast.findByType(NodeType.LIST)).hasSize(1);
        ListNode list = (ListNode) ast.findByType(NodeType.LIST).get(0);
        System.out.println(ast);
        System.out.println(list);
        assertThat(list.isOrdered()).isTrue();
        assertThat(list.getItems()).hasSize(3);
    }

    @Test
    @DisplayName("解析嵌套列表")
    void shouldParseNestedList() throws DocumentParseException {
        String md = "- parent 1\n  - child 1\n  - child 2\n- parent 2";
        DocumentAST ast = parse(md);
        assertThat(ast.findByType(NodeType.LIST)).hasSize(2);
        ListNode outer = (ListNode) ast.findByType(NodeType.LIST).get(0);
        System.out.println(ast);
        System.out.println(outer);
        assertThat(outer.getItems()).hasSize(2);
    }

    // ——— Quote tests ———

    @Test
    @DisplayName("解析引用为 QuoteNode")
    void shouldParseQuote() throws DocumentParseException {
        String md = "# Title\n\n> This is a quote";
        DocumentAST ast = parse(md);
        assertThat(ast.findByType(NodeType.QUOTE)).hasSize(1);
        QuoteNode quote = (QuoteNode) ast.findByType(NodeType.QUOTE).get(0);
        System.out.println(ast);
        System.out.println(quote);
        assertThat(quote.text()).contains("This is a quote");
    }

    @Test
    @DisplayName("多行连续引用合并为一个 QuoteNode")
    void shouldParseMultiLineQuote() throws DocumentParseException {
        String md = "> line one\n> line two\n\nNormal text";
        DocumentAST ast = parse(md);
        System.out.println(ast);
        assertThat(ast.findByType(NodeType.QUOTE)).hasSize(1);
    }

    private DocumentAST parse(String md) throws DocumentParseException {
        DocumentInput input = DocumentInput.builder()
                .rawBytes(md.getBytes(StandardCharsets.UTF_8))
                .fileName("test.md")
                .size(md.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN).mimeType("text/markdown").build();
        return parser.parseToAST(input, info);
    }
}
