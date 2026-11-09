package com.yin.ai.platform.core.memory.working;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作记忆 — Agent 运行时活动的 Scratchpad。
 * <p>
 * 保存计划树、变量作用域、中间结果和恢复点。状态机通过 CAS 原子操作转换：
 * ACTIVE → SUSPENDED → RESUMING → ACTIVE（恢复流程）
 * ACTIVE → COMPLETED / FAILED（终态）
 * <p>
 * 设计理由：不继承任何框架类，纯 Java 对象，可序列化到 Redis L2 用于故障恢复。
 *
 * @see PlanNode
 * @see VariableScope
 * @see ResumePoint
 */
public class WorkingMemory {
    private final MemoryId id;
    private final PlanNode rootPlan;
    private final VariableScope globalScope;
    private final ArrayDeque<VariableScope> scopeStack;
    private final List<IntermediateResult> intermediateResults;
    private final List<ResumePoint> resumePoints;
    private volatile WorkingMemoryState state;
    private final Instant createdAt;

    public WorkingMemory(MemoryId id, PlanNode rootPlan) {
        this.id = id;
        this.rootPlan = rootPlan;
        this.globalScope = new VariableScope("global");
        this.scopeStack = new ArrayDeque<>();
        this.intermediateResults = new ArrayList<>();
        this.resumePoints = new ArrayList<>();
        this.state = WorkingMemoryState.ACTIVE;
        this.createdAt = Instant.now();
    }

    public MemoryId getId() {
        return id;
    }

    public PlanNode getRootPlan() {
        return rootPlan;
    }

    public WorkingMemoryState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized boolean transitionTo(WorkingMemoryState target) {
        if (state.isTerminal()) {
            return false;
        }
        state = target;
        return true;
    }

    public void putVariable(String key, Object value) {
        if (!scopeStack.isEmpty()) {
            scopeStack.peek().put(key, value);
        } else {
            globalScope.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getVariable(String key, Class<T> type) {
        if (!scopeStack.isEmpty()) {
            Optional<T> local = scopeStack.peek().get(key, type);
            if (local.isPresent()) {
                return local;
            }
        }
        return globalScope.get(key, type);
    }

    public Optional<Object> resolveVariable(String key) {
        if (!scopeStack.isEmpty()) {
            Optional<Object> local = scopeStack.peek().resolve(key);
            if (local.isPresent()) {
                return local;
            }
        }
        return globalScope.resolve(key);
    }

    public VariableScope pushScope(String name) {
        VariableScope parent = scopeStack.isEmpty() ? globalScope : scopeStack.peek();
        VariableScope scope = new VariableScope(name, parent);
        scopeStack.push(scope);
        return scope;
    }

    public VariableScope popScope() {
        if (scopeStack.isEmpty()) {
            throw new IllegalStateException("No scope to pop");
        }
        return scopeStack.pop();
    }

    public VariableScope currentScope() {
        return scopeStack.isEmpty() ? globalScope : scopeStack.peek();
    }

    public void recordResult(IntermediateResult result) {
        intermediateResults.add(result);
    }

    public List<IntermediateResult> getIntermediateResults() {
        return List.copyOf(intermediateResults);
    }

    public ResumePoint createResumePoint(String label) {
        String currentNodeId = findCurrentPlanNode(rootPlan)
                .map(PlanNode::nodeId)
                .orElse(rootPlan.nodeId());
        ResumePoint point = ResumePoint.of(label, currentNodeId,
                globalScope.snapshot(), List.copyOf(intermediateResults));
        resumePoints.add(point);
        return point;
    }

    public Optional<ResumePoint> findResumePoint(String label) {
        return resumePoints.stream()
                .filter(rp -> rp.label().equals(label))
                .reduce((first, second) -> second);
    }

    public List<ResumePoint> getResumePoints() {
        return List.copyOf(resumePoints);
    }

    private Optional<PlanNode> findCurrentPlanNode(PlanNode node) {
        if (node.status() == PlanNodeStatus.IN_PROGRESS) {
            for (PlanNode child : node.children()) {
                Optional<PlanNode> found = findCurrentPlanNode(child);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.of(node);
        }
        return Optional.empty();
    }

    public Map<String, Object> snapshotVariables() {
        return globalScope.snapshot();
    }

    @Override
    public String toString() {
        return "WorkingMemory[" + id + ", state=" + state
                + ", planNodes=" + countPlanNodes(rootPlan)
                + ", results=" + intermediateResults.size()
                + ", resumePoints=" + resumePoints.size() + "]";
    }

    private int countPlanNodes(PlanNode node) {
        int count = 1;
        for (PlanNode child : node.children()) {
            count += countPlanNodes(child);
        }
        return count;
    }
}
