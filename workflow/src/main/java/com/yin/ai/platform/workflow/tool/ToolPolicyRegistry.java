package com.yin.ai.platform.workflow.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具策略注册表 — 收集全局 Tool Policy（工作流内嵌策略在解析时优先生效）。
 */
@Component
public class ToolPolicyRegistry {

    private final Map<String, ToolPolicy> policies = new HashMap<>();

    public ToolPolicyRegistry(List<ToolPolicy> policies) {
        for (ToolPolicy policy : policies) {
            if (policy != null) {
                this.policies.put(policy.name(), policy);
            }
        }
    }

    public void register(ToolPolicy policy) {
        policies.put(policy.name(), policy);
    }

    public Optional<ToolPolicy> get(String name) {
        return Optional.ofNullable(policies.get(name));
    }

    public boolean contains(String name) {
        return policies.containsKey(name);
    }

    /**
     * 解析某步骤允许的工具集：优先工作流内嵌策略，其次全局注册表，最后为空集。
     */
    public Set<String> resolveAllowedTools(
            com.yin.ai.platform.workflow.model.WorkflowDefinition workflow, String policyName) {
        if (policyName == null || policyName.isBlank()) {
            return Set.of();
        }
        if (workflow != null && workflow.getToolPolicies() != null
                && workflow.getToolPolicies().containsKey(policyName)) {
            return workflow.getToolPolicies().get(policyName).allowedToolsOrEmpty();
        }
        ToolPolicy global = policies.get(policyName);
        return global != null ? global.allowedToolsOrEmpty() : Set.of();
    }
}
