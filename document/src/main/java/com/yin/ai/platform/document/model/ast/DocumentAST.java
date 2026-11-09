package com.yin.ai.platform.document.model.ast;

import java.util.ArrayDeque;
import java.util.ArrayList;

import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Document AST — 文档抽象语法树容器。
 * <p>
 * 维护 root + nodeIndex (O(1) 查找)，同步 sibling/depth 关系。
 * 所有 Pipeline Processor 操作此对象。
 * <p>
 * <b>Not thread-safe.</b> 每个文档应创建独立的 AST 实例，
 * 不应在多个线程间共享同一个 AST。
 */
public class DocumentAST {

    /**
     * 文档唯一标识
     */
    private String documentId;
    /**
     * 树的根节点
     */
    private RootNode root;
    /**
     * ID → Node 的 O(1) 查找表。
     */
    private Map<String, DocumentNode> nodeIndex;
    /**
     * DFS 前序遍历的节点ID序列
     */
    private List<String> nodeSequence;

    public DocumentAST() {
        this.nodeIndex = new HashMap<>();
        this.nodeSequence = new ArrayList<>();
    }

    /**
     * 用给定的 root 初始化 AST，自动建立索引。
     */
    public static DocumentAST of(RootNode root) {
        DocumentAST ast = new DocumentAST();
        ast.root = root;
        //构建Node的查找表
        ast.indexNode(root);
        ast.rebuildLinks();
        return ast;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public RootNode getRoot() {
        return root;
    }

    //直接通过id去获取节点
    public DocumentNode getNode(String id) {
        return nodeIndex.get(id);
    }

    public int nodeCount() {
        return nodeIndex.size();
    }

    //模式A：DFS 前序（文档阅读顺序）
    public Stream<DocumentNode> stream() {
        return nodeSequence.stream().map(nodeIndex::get);
    }

    // 模式B：BFS 层序（按层级处理）
    public Stream<DocumentNode> streamBfs() {
        List<DocumentNode> result = new ArrayList<>();
        Deque<DocumentNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            // 队首出队，返回头部元素，队列为空返回 null
            DocumentNode node = queue.poll();
            result.add(node);
            // 当前节点所有子节点全部加入队列尾部
            queue.addAll(node.getChildren());
        }
        return result.stream();
    }

    /**
     * 按节点类型过滤 — 遍历全部节点，O(N)。
     */
    public List<DocumentNode> findByType(NodeType type) {
        return stream().filter(n -> n.getNodeType() == type).toList();
    }

    /**
     * 获取所有 Section 节点（按 DFS 顺序）。
     */
    public List<SectionNode> getSections() {
        return findByType(NodeType.SECTION).stream()
                .map(n -> (SectionNode) n).toList();
    }

    /**
     * 获取所有 Paragraph 节点（按 DFS 顺序）。
     */
    public List<ParagraphNode> getParagraphs() {
        return findByType(NodeType.PARAGRAPH).stream()
                .map(n -> (ParagraphNode) n).toList();
    }

    @SuppressWarnings("unchecked")
    public List<TableNode> getTables() {
        return findByType(NodeType.TABLE).stream()
                .map(n -> (TableNode) n).toList();
    }

    /**
     * 向父节点添加子节点，自动维护索引和 sibling/depth 关系。
     */
    public void addChild(String parentId, DocumentNode child) {
        DocumentNode parent = nodeIndex.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent node not found: " + parentId);
        }
        addChildToParent(parent, child);
    }

    /**
     * 在指定位置插入子节点。
     */
    public void addChildAt(String parentId, int index, DocumentNode child) {
        DocumentNode parent = nodeIndex.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent node not found: " + parentId);
        }
        parent.getChildren().add(index, child);
        child.setParent(parent);
        indexNode(child);
        rebuildLinks();
    }

    /**
     * 批量添加子节点 — 先批量修改树结构，最后只调用一次 rebuildLinks。
     * <p>
     * 替代循环调用 {@link #addChild(String, DocumentNode)} 的低效模式，
     * 将 N 次全量重建降为 1 次，总复杂度从 O(N × M) 降为 O(M)。
     *
     * @param parentId 父节点 ID
     * @param children 待添加的子节点列表
     * @throws IllegalArgumentException 如果父节点不存在
     */
    public void batchAddChildren(String parentId, List<DocumentNode> children) {
        DocumentNode parent = nodeIndex.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent node not found: " + parentId);
        }
        for (DocumentNode child : children) {
            parent.addChild(child);
            indexNode(child);
        }
        rebuildLinks();
    }

    /**
     * 移除节点及其子树 — 先定位索引再移除，避免 ArrayList.remove(Object) 的 O(n) equals 扫描。
     */
    public void removeNode(String nodeId) {
        DocumentNode node = nodeIndex.get(nodeId);
        if (node == null) {
            return;
        }
        DocumentNode parent = node.getParent();
        if (parent != null) {
            int idx = parent.getChildren().indexOf(node);
            if (idx >= 0) {
                parent.getChildren().remove(idx);
            }
        }
        removeSubtreeFromIndex(node);
        rebuildLinks();
    }

    /**
     * 内部方法：添加单个子节点并触发全量重建。
     * 注意：循环添加请使用 {@link #batchAddChildren} 避免 N 次全量重建。
     */
    void addChildToParent(DocumentNode parent, DocumentNode child) {
        parent.addChild(child);
        indexNode(child);
        rebuildLinks();
    }

    /**
     * 全量重建 sibling/depth 关系和 DFS 遍历序列 — O(M)，M = 节点总数。
     * 每次添加/移除节点后调用，频繁修改时优先使用 {@link #batchAddChildren}。
     */
    void rebuildLinks() {
        if (root == null) {
            return;
        }
        nodeSequence.clear();
        rebuildLinksRecursive(root, 0);
    }

    private void rebuildLinksRecursive(DocumentNode node, int depth) {
        node.setDepth(depth);
        nodeSequence.add(node.getId());

        List<DocumentNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            DocumentNode child = children.get(i);
            //设置子节点的sibling顺序，例如 0->1->2,当前获取到的是2这个节点，那么就将1设置为2的previousSibling
            child.setPreviousSibling(i > 0 ? children.get(i - 1) : null);
            //设计子节点的下一个节点的sibling顺序，例如： 0-> 1->2->3，当前获取到的是2这个节点，那么就将3设置为2的nextSibling
            child.setNextSibling(i < children.size() - 1 ? children.get(i + 1) : null);
            //重复构建主节点的sibling关系，知道所有的子节点都处理完毕，depth+1意味着我们这个节点是下一个要处理节点的父节点
            rebuildLinksRecursive(child, depth + 1);
        }
    }

    /**
     * 递归索引节点及其所有子节点。
     */
    private void indexNode(DocumentNode node) {
        nodeIndex.put(node.getId(), node);
        for (DocumentNode child : node.getChildren()) {
            indexNode(child);
        }
    }

    /**
     * 递归移除节点及其子树的所有索引条目。
     */
    private void removeSubtreeFromIndex(DocumentNode node) {
        nodeIndex.remove(node.getId());
        for (DocumentNode child : node.getChildren()) {
            removeSubtreeFromIndex(child);
        }
    }

    /**
     * 计算全部叶子节点的文本总长度 — 仅统计无子节点的叶子，避免重复计算。
     */
    public long totalTextLength() {
        return stream()
                .filter(n -> n.getChildren().isEmpty())
                .mapToInt(n -> n.text().length())
                .sum();
    }

    public int sectionCount() {
        return findByType(NodeType.SECTION).size();
    }

    public int tableCount() {
        return findByType(NodeType.TABLE).size();
    }

    @Override
    public String toString() {
        return "DocumentAST{" + "nodes=" + nodeIndex.size() + ", sections="
                + sectionCount() + ", tables=" + tableCount() + '}';
    }
}
