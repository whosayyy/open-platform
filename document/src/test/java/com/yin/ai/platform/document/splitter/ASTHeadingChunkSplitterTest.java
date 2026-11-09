package com.yin.ai.platform.document.splitter;

import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import com.yin.ai.platform.document.model.ast.SectionNode;
import com.yin.ai.platform.document.model.chunk.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ASTHeadingChunkSplitter")
class ASTHeadingChunkSplitterTest {

    private ASTHeadingChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new ASTHeadingChunkSplitter();
    }

    @Test
    @DisplayName("按 Section 边界切分")
    void shouldSplitBySection() {
        RootNode root = new RootNode();
        root.setTitle("Doc");

        SectionNode s1 = new SectionNode();
        s1.setHeading("Intro");
        s1.setLevel(1);
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("Text 1");
        s1.addChild(p1);
        root.addChild(s1);

        SectionNode s2 = new SectionNode();
        s2.setHeading("Details");
        s2.setLevel(1);
        ParagraphNode p2 = new ParagraphNode();
        p2.setText("Text 2");
        s2.addChild(p2);
        root.addChild(s2);

        DocumentAST ast = DocumentAST.of(root);

        List<DocumentChunk> chunks = splitter.splitAST(ast);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getHeadingPath()).isEqualTo("Intro");
        assertThat(chunks.get(1).getHeadingPath()).isEqualTo("Details");
    }

    @Test
    @DisplayName("Chunk 引用 AST Node ID")
    void shouldReferenceASTNodes() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        s1.setHeading("Section 1");
        s1.setLevel(1);
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("Hello");
        s1.addChild(p1);
        root.addChild(s1);

        DocumentAST ast = DocumentAST.of(root);

        List<DocumentChunk> chunks = splitter.splitAST(ast);

        assertThat(chunks).hasSize(1);
        DocumentChunk chunk = chunks.get(0);
        assertThat(chunk.getNodeRefs()).contains(s1.getId(), p1.getId());
    }

    @Test
    @DisplayName("无 Section 时创建一个 Chunk")
    void shouldCreateSingleChunkWhenNoSections() {
        RootNode root = new RootNode();
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("Preamble text");
        root.addChild(p1);

        DocumentAST ast = DocumentAST.of(root);

        List<DocumentChunk> chunks = splitter.splitAST(ast);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).contains("Preamble text");
    }

    @Test
    @DisplayName("空 AST 返回空 Chunk 列表")
    void shouldReturnEmptyChunksForEmptyAST() {
        RootNode root = new RootNode();
        DocumentAST ast = DocumentAST.of(root);

        List<DocumentChunk> chunks = splitter.splitAST(ast);

        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("Chunk 内容从 AST 实时计算")
    void shouldComputeContentFromAST() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        s1.setHeading("Chapter 1");
        s1.setLevel(1);
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("This is chapter one.");
        s1.addChild(p1);
        root.addChild(s1);

        DocumentAST ast = DocumentAST.of(root);

        List<DocumentChunk> chunks = splitter.splitAST(ast);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).contains("Chapter 1");
        assertThat(chunks.get(0).getContent()).contains("This is chapter one.");
    }
}
