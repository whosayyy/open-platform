package com.yin.ai.platform.document.model.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentAST")
class DocumentASTTest {

    @Test
    @DisplayName("构建 Section → Paragraph 两层树")
    void shouldBuildTwoLevelTree() {
        RootNode root = new RootNode();
        root.setTitle("Test Doc");

        SectionNode section = new SectionNode();
        section.setHeading("Introduction");
        section.setLevel(1);

        ParagraphNode para = new ParagraphNode();
        para.setText("Hello World");

        section.addChild(para);
        root.addChild(section);

        DocumentAST ast = DocumentAST.of(root);

        assertThat(ast.nodeCount()).isEqualTo(3);
        assertThat(ast.sectionCount()).isEqualTo(1);
        assertThat(ast.getParagraphs()).hasSize(1);
        assertThat(ast.getParagraphs().get(0).text()).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("parent/children 关系正确")
    void shouldMaintainParentChildRelations() {
        RootNode root = new RootNode();
        SectionNode section = new SectionNode();
        root.addChild(section);

        assertThat(section.getParent()).isSameAs(root);
        assertThat(root.getChildren()).containsExactly(section);
    }

    @Test
    @DisplayName("sibling 关系正确")
    void shouldMaintainSiblingRelations() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        s1.setHeading("A");
        SectionNode s2 = new SectionNode();
        s2.setHeading("B");
        root.addChild(s1);
        root.addChild(s2);

        DocumentAST ast = DocumentAST.of(root);

        assertThat(s1.getNextSibling()).isSameAs(s2);
        assertThat(s2.getPreviousSibling()).isSameAs(s1);
        assertThat(s1.getPreviousSibling()).isNull();
        assertThat(s2.getNextSibling()).isNull();
    }

    @Test
    @DisplayName("depth 计算正确")
    void shouldComputeDepth() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("text");
        s1.addChild(p1);
        root.addChild(s1);

        DocumentAST ast = DocumentAST.of(root);

        assertThat(root.getDepth()).isEqualTo(0);
        assertThat(s1.getDepth()).isEqualTo(1);
        assertThat(p1.getDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("findByType 正确过滤节点类型")
    void shouldFilterByType() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("a");
        ParagraphNode p2 = new ParagraphNode();
        p2.setText("b");
        s1.addChild(p1);
        s1.addChild(p2);
        root.addChild(s1);

        DocumentAST ast = DocumentAST.of(root);

        List<DocumentNode> paragraphs = ast.findByType(NodeType.PARAGRAPH);
        assertThat(paragraphs).hasSize(2);
    }

    @Test
    @DisplayName("DFS 遍历顺序正确")
    void shouldTraverseInDFSOrder() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("a");
        s1.addChild(p1);
        root.addChild(s1);

        DocumentAST ast = DocumentAST.of(root);

        List<DocumentNode> nodes = ast.stream().toList();
        assertThat(nodes).containsExactly(root, s1, p1);
    }

    @Test
    @DisplayName("节点移除后索引更新")
    void shouldRemoveNodeAndUpdateIndex() {
        RootNode root = new RootNode();
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("remove me");
        root.addChild(p1);

        DocumentAST ast = DocumentAST.of(root);
        assertThat(ast.nodeCount()).isEqualTo(2);

        ast.removeNode(p1.getId());
        assertThat(ast.nodeCount()).isEqualTo(1);
        assertThat(ast.getNode(p1.getId())).isNull();
    }

    @Test
    @DisplayName("totalTextLength 正确计算")
    void shouldCalculateTotalTextLength() {
        RootNode root = new RootNode();
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("Hello");
        ParagraphNode p2 = new ParagraphNode();
        p2.setText("World");
        root.addChild(p1);
        root.addChild(p2);

        DocumentAST ast = DocumentAST.of(root);

        assertThat(ast.totalTextLength()).isEqualTo(10);
    }

    @Test
    @DisplayName("findAncestorSection 找到最近的 Section 祖先")
    void shouldFindAncestorSection() {
        RootNode root = new RootNode();
        SectionNode s1 = new SectionNode();
        s1.setHeading("Section 1");
        ParagraphNode p1 = new ParagraphNode();
        p1.setText("text");
        s1.addChild(p1);
        root.addChild(s1);

        DocumentAST.of(root);

        SectionNode found = p1.findAncestorSection();
        assertThat(found).isSameAs(s1);
        assertThat(found.getHeading()).isEqualTo("Section 1");
    }

    @Test
    @DisplayName("TableNode 渲染 Markdown 表格")
    void shouldRenderTableToMarkdown() {
        TableNode table = new TableNode();
        table.setHeaders(List.of("Name", "Age"));
        table.setColumnAlignments(List.of("LEFT", "RIGHT"));

        TableRowNode row = new TableRowNode();
        TableCellNode c1 = new TableCellNode();
        c1.setText("Alice");
        TableCellNode c2 = new TableCellNode();
        c2.setText("30");
        row.getCells().add(c1);
        row.getCells().add(c2);
        row.addChild(c1);
        row.addChild(c2);
        table.getRows().add(row);
        table.addChild(row);

        String md = table.text();
        assertThat(md).contains("Name");
        assertThat(md).contains("Alice");
        assertThat(md).contains(":---");
    }
}
