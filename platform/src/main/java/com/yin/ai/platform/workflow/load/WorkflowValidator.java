package com.yin.ai.platform.workflow.load;

import com.yin.ai.platform.workflow.executor.ApplicationStepHandlerRegistry;
import com.yin.ai.platform.workflow.executor.StepExecutorRegistry;
import com.yin.ai.platform.workflow.model.ExecutorType;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.workflow.prompt.StagePromptRegistry;
import com.yin.ai.platform.workflow.tool.ToolPolicyRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流定义校验器 — 结构、依赖、执行器、Handler、Tool Policy、Stage Prompt 与环检测。
 */
@Component
public class WorkflowValidator {

    private final StagePromptRegistry stagePromptRegistry;
    private final ToolPolicyRegistry toolPolicyRegistry;
    private final ApplicationStepHandlerRegistry handlerRegistry;
    private final StepExecutorRegistry executorRegistry;

    public WorkflowValidator(StagePromptRegistry stagePromptRegistry,
                             ToolPolicyRegistry toolPolicyRegistry,
                             ApplicationStepHandlerRegistry handlerRegistry,
                             StepExecutorRegistry executorRegistry) {
        this.stagePromptRegistry = stagePromptRegistry;
        this.toolPolicyRegistry = toolPolicyRegistry;
        this.handlerRegistry = handlerRegistry;
        this.executorRegistry = executorRegistry;
    }

    /**
     * 返回全部校验错误；空表示合法。
     */
    public List<String> validate(WorkflowDefinition wf) {
        List<String> errors = new ArrayList<>();
        if (wf.getName() == null || wf.getName().isBlank()) {
            errors.add("workflow name is blank");
        }
        if (wf.getVersion() == null || wf.getVersion().isBlank()) {
            errors.add("workflow version is blank");
        }
        if (wf.getSteps() == null || wf.getSteps().isEmpty()) {
            errors.add("workflow has no steps");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (StepDefinition step : wf.getSteps()) {
            if (step.getId() == null || step.getId().isBlank()) {
                errors.add("step has blank id");
                continue;
            }
            if (!ids.add(step.getId())) {
                errors.add("duplicate step id: " + step.getId());
            }
            validateStep(wf, step, errors);
        }
        for (StepDefinition step : wf.getSteps()) {
            for (String dep : step.getDependsOn()) {
                if (dep.equals(step.getId())) {
                    errors.add("step " + step.getId() + " depends on itself");
                } else if (!ids.contains(dep)) {
                    errors.add("step " + step.getId() + " depends on unknown step: " + dep);
                }
            }
        }
        try {
            wf.orderedSteps();
        } catch (Exception e) {
            errors.add("workflow contains a cycle: " + e.getMessage());
        }
        return errors;
    }

    private void validateStep(WorkflowDefinition wf, StepDefinition step, List<String> errors) {
        if (step.getExecutorType() == null) {
            errors.add("step " + step.getId() + " has no executor");
            return;
        }
        if (!executorRegistry.supports(step.getExecutorType())) {
            errors.add("step " + step.getId() + " unsupported executor: " + step.getExecutorType());
        }
        if (step.getExecutorType() == ExecutorType.APPLICATION) {
            if (step.getHandler() == null || step.getHandler().isBlank()) {
                errors.add("step " + step.getId() + " APPLICATION step requires handler");
            } else if (!handlerRegistry.contains(step.getHandler())) {
                errors.add("step " + step.getId() + " unknown handler: " + step.getHandler());
            }
        }
        if ((step.getExecutorType() == ExecutorType.LLM || step.getExecutorType() == ExecutorType.AGENT)
                && step.outputType() == null) {
            errors.add("step " + step.getId() + " " + step.getExecutorType() + " step requires output artifact");
        }
        if (step.getPrompt() != null && !step.getPrompt().isBlank()
                && !stagePromptRegistry.exists(step.getPrompt())) {
            errors.add("step " + step.getId() + " unknown stage prompt: " + step.getPrompt());
        }
        if (step.getToolPolicy() != null && !step.getToolPolicy().isBlank()) {
            boolean embedded = wf.getToolPolicies() != null
                    && wf.getToolPolicies().containsKey(step.getToolPolicy());
            if (!embedded && !toolPolicyRegistry.contains(step.getToolPolicy())) {
                errors.add("step " + step.getId() + " unknown tool policy: " + step.getToolPolicy());
            }
        }
    }
}
