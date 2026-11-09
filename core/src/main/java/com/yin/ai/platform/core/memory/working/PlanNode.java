package com.yin.ai.platform.core.memory.working;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 计划树节点 — Agent 执行计划的基本单元。
 * <p>
 * 支持嵌套：GOAL → SUBGOAL → ACTION 形成树状结构。
 * 支持依赖：dependencies 字段声明前置节点，用于拓扑排序执行。
 * 不可变 record：所有 with* 方法返回新实例，状态变更通过 PlanNode 重建实现。
 */
public record PlanNode(
        String nodeId,
        String description,
        PlanNodeType type,
        PlanNodeStatus status,
        List<PlanNode> children,
        Map<String, Object> parameters,
        Map<String, Object> expectedOutput,
        Map<String, Object> actualOutput,
        Instant createdAt,
        Instant completedAt,
        String assignedTool,
        List<String> dependencies) {

    public PlanNode {
        children = children != null ? List.copyOf(children) : List.of();
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        expectedOutput = expectedOutput != null ? Map.copyOf(expectedOutput) : Map.of();
        actualOutput = actualOutput != null ? Map.copyOf(actualOutput) : Map.of();
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
    }

    public static PlanNode goal(String nodeId, String description) {
        return new PlanNode(nodeId, description, PlanNodeType.GOAL, PlanNodeStatus.PENDING,
                List.of(), Map.of(), Map.of(), Map.of(), Instant.now(), null, null, List.of());
    }

    public static PlanNode action(String nodeId, String description, String toolName,
                                   Map<String, Object> parameters) {
        return new PlanNode(nodeId, description, PlanNodeType.ACTION, PlanNodeStatus.PENDING,
                List.of(), parameters, Map.of(), Map.of(), Instant.now(), null, toolName, List.of());
    }

    public PlanNode withStatus(PlanNodeStatus newStatus) {
        Instant completed = newStatus.isTerminal() ? Instant.now() : null;
        return new PlanNode(nodeId, description, type, newStatus, children,
                parameters, expectedOutput, actualOutput, createdAt, completed, assignedTool, dependencies);
    }

    public PlanNode withActualOutput(Map<String, Object> output) {
        return new PlanNode(nodeId, description, type, status, children,
                parameters, expectedOutput, output, createdAt, completedAt, assignedTool, dependencies);
    }

    public PlanNode withChildren(List<PlanNode> newChildren) {
        return new PlanNode(nodeId, description, type, status,
                List.copyOf(newChildren), parameters, expectedOutput, actualOutput,
                createdAt, completedAt, assignedTool, dependencies);
    }

    public PlanNode addChild(PlanNode child) {
        List<PlanNode> newChildren = new ArrayList<>(children);
        newChildren.add(child);
        return withChildren(newChildren);
    }

    public boolean allDependenciesSatisfied() {
        return dependencies.isEmpty();
    }

    public boolean isExecutable() {
        return status == PlanNodeStatus.PENDING || status == PlanNodeStatus.IN_PROGRESS;
    }
}
