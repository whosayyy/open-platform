package com.yin.ai.platform.workflow.load;

import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认工作流注册表 — 启动时从 {@link WorkflowLoader} 加载并校验。
 *
 * <p>校验失败的 Workflow 不会注册，避免运行时歧义。
 */
@Slf4j
@Component
public class DefaultWorkflowRegistry implements WorkflowRegistry {

    private final Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();

    public DefaultWorkflowRegistry(WorkflowLoader loader, WorkflowValidator validator) {
        List<WorkflowDefinition> loaded = loader.loadAll();
        for (WorkflowDefinition workflow : loaded) {
            List<String> errors = validator.validate(workflow);
            if (errors.isEmpty()) {
                workflows.put(workflow.getName(), workflow);
                log.info("[WORKFLOW-REG] registered workflow: name={} version={} steps={}",
                        workflow.getName(), workflow.getVersion(), workflow.getSteps().size());
            } else {
                log.error("[WORKFLOW-REG] workflow {} invalid, skipped: {}", workflow.getName(), errors);
            }
        }
        if (workflows.isEmpty()) {
            log.warn("[WORKFLOW-REG] no workflow registered (check classpath*:workflows/*.yaml)");
        }
    }

    @Override
    public WorkflowDefinition get(String name) {
        WorkflowDefinition workflow = workflows.get(name);
        if (workflow == null) {
            throw new IllegalStateException("workflow not found: " + name);
        }
        return workflow;
    }

    @Override
    public Optional<WorkflowDefinition> find(String name) {
        return Optional.ofNullable(workflows.get(name));
    }

    @Override
    public List<WorkflowDefinition> all() {
        return List.copyOf(workflows.values());
    }
}
