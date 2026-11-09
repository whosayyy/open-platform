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

@DisplayName("TxtDocumentParser.parseToAST")
class TxtASTParserTest {

    private TxtDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new TxtDocumentParser();
    }

    @Test
    @DisplayName("按空行分段为 ParagraphNode")
    void shouldSplitParagraphsByBlankLines() throws DocumentParseException {
        String txt = "Paragraph one.\n\nParagraph two.\n\nParagraph three.";
        DocumentAST ast = parse(txt);
        assertThat(ast.findByType(NodeType.PARAGRAPH)).hasSize(3);
    }

    @Test
    @DisplayName("单段文本产生一个 ParagraphNode")
    void shouldCreateSingleParagraph() throws DocumentParseException {
        String txt = "This is a single paragraph.";
        DocumentAST ast = parse(txt);
        assertThat(ast.findByType(NodeType.PARAGRAPH)).hasSize(1);
    }

    @Test
    @DisplayName("空文本创建空 AST")
    void shouldCreateEmptyAST() throws DocumentParseException {
        String txt = "";
        DocumentAST ast = parse(txt);
        assertThat(ast.nodeCount()).isEqualTo(1); // root only
    }

    @Test
    @DisplayName("多空行不影响 Paragraph 数量")
    void shouldHandleMultipleBlankLines() throws DocumentParseException {
        String txt = "Para one.\n\n\n\nPara two.";
        DocumentAST ast = parse(txt);
        assertThat(ast.findByType(NodeType.PARAGRAPH)).hasSize(2);
    }

    @Test
    @DisplayName("提取文件名为标题")
    void shouldExtractTitleFromFileName() throws DocumentParseException {
        String txt = "Content here.";
        DocumentAST ast = parse(txt);
        assertThat(ast.getRoot().getTitle()).isEqualTo("test");
    }

    private DocumentAST parse(String txt) throws DocumentParseException {
        DocumentInput input = DocumentInput.builder()
                .rawBytes(txt.getBytes(StandardCharsets.UTF_8))
                .fileName("test.txt")
                .size(txt.length())
                .build();
        DocumentTypeInfo info = DocumentTypeInfo.builder()
                .documentType(DocumentType.TXT).mimeType("text/plain").build();
        return parser.parseToAST(input, info);
    }
}
