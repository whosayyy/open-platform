package com.yin.ai.platform.workflow.executor;

import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.UserInput;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.workflow.prompt.StepContext;

import java.util.List;
import java.util.Set;

/**
 * 单次步骤执行的完整上下文。
 */
public record StepExecutionContext(
        TaskRun taskRun,
        StepRun stepRun,
        StepDefinition step,
        WorkflowDefinition workflow,
        List<Artifact> inputArtifacts,
        Set<String> allowedTools,
        StepContext prompt,
        UserInput userInput
) {

    public Artifact inputOf(com.yin.ai.platform.workflow.model.ArtifactType type) {
        if (inputArtifacts == null) {
            return null;
        }
        return inputArtifacts.stream()
                .filter(a -> a.type() == type)
                .findFirst()
                .orElse(null);
    }

    public String systemPrompt() {
        return prompt != null ? prompt.systemPrompt() : null;
    }

    public String userPrompt() {
        return prompt != null ? prompt.userPrompt() : null;
    }
}
