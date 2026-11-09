package com.yin.ai.platform.workflow.model;

import com.yin.ai.platform.workflow.tool.ToolPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流定义 — 描述步骤、依赖、执行器、Tool Policy 与产物。
 *
 * <p>工作流定义只描述"做什么"，不包含任何 LLM 流程控制指令。
 */
public class WorkflowDefinition {

    private String name;
    private String version;
    private String description;
    /** 使用的薄 Skill 名称（写入任务上下文，用于 Stage Prompt 之上的角色定义） */
    private String skill;
    private List<StepDefinition> steps = new ArrayList<>();
    /** 本工作流内嵌的 Tool Policy（key = policy 名） */
    private Map<String, ToolPolicy> toolPolicies = new HashMap<>();

    public WorkflowDefinition() {
    }

    public WorkflowDefinition(String name, String version, String description, List<StepDefinition> steps) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.steps = steps;
    }

    public StepDefinition step(String id) {
        for (StepDefinition s : steps) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** 按执行顺序遍历步骤（拓扑序） */
    public List<StepDefinition> orderedSteps() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (StepDefinition s : steps) {
            inDegree.putIfAbsent(s.getId(), 0);
            adj.putIfAbsent(s.getId(), new ArrayList<>());
        }
        for (StepDefinition s : steps) {
            for (String dep : s.getDependsOn()) {
                adj.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.getId());
            }
        }
        for (StepDefinition s : steps) {
            inDegree.put(s.getId(), s.getDependsOn().size());
        }
        List<StepDefinition> order = new ArrayList<>();
        while (order.size() < steps.size()) {
            String ready = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (ready == null) {
                throw new IllegalStateException("workflow has a cycle: " + name);
            }
            inDegree.put(ready, -1);
            order.add(step(ready));
            for (String next : adj.getOrDefault(ready, List.of())) {
                inDegree.put(next, inDegree.get(next) - 1);
            }
        }
        return order;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps == null ? new ArrayList<>() : steps;
    }

    public Map<String, ToolPolicy> getToolPolicies() {
        return toolPolicies;
    }

    public void setToolPolicies(Map<String, ToolPolicy> toolPolicies) {
        this.toolPolicies = toolPolicies == null ? new HashMap<>() : toolPolicies;
    }
}
