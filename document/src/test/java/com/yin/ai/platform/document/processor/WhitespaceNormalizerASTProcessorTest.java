package com.yin.ai.platform.document.processor;

import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WhitespaceNormalizerASTProcessor")
class WhitespaceNormalizerASTProcessorTest {

    private final WhitespaceNormalizerASTProcessor processor = new WhitespaceNormalizerASTProcessor();

    @Test
    @DisplayName("统一换行符并去除行尾空白")
    void shouldNormalizeLineEndingsAndStripTrailing() {
        RootNode root = new RootNode();
        ParagraphNode para = new ParagraphNode();
        para.setText("line one  \r\nline two\r\n  \r\n\r\nline three\r\n");
        root.addChild(para);
        DocumentAST ast = DocumentAST.of(root);

        processor.process(ast);

        String result = ((ParagraphNode) root.getChildren().get(0)).text();
        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("  \n");
    }

    @Test
    @DisplayName("压缩连续空行")
    void shouldCompressExcessiveBlankLines() {
        RootNode root = new RootNode();
        ParagraphNode para = new ParagraphNode();
        para.setText("paragraph\n\n\n\n\n\ntoo many blanks");
        root.addChild(para);
        DocumentAST ast = DocumentAST.of(root);

        processor.process(ast);

        String result = ((ParagraphNode) root.getChildren().get(0)).text();
        assertThat(result).doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("空 AST 不抛异常")
    void shouldHandleEmptyAST() {
        DocumentAST ast = DocumentAST.of(new RootNode());
        processor.process(ast); // no exception
    }

    @Test
    @DisplayName("order 为 10")
    void shouldReturnOrder10() {
        assertThat(processor.order()).isEqualTo(10);
    }

    @Test
    @DisplayName("静态 normalizeWhitespace 方法正确工作")
    void shouldNormalizeWhitespaceStatically() {
        String input = "hello  \r\n\r\n\r\nworld\r\n";
        String result = WhitespaceNormalizerASTProcessor.normalizeWhitespace(input);
        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("\n\n\n");
        assertThat(result).startsWith("hello");
        assertThat(result).endsWith("world\n");
    }
}
