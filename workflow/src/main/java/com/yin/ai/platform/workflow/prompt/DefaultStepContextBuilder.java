package com.yin.ai.platform.workflow.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.UserInput;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.workflow.tool.ToolPolicyRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 默认步骤上下文构建器 — 薄 Skill + 当前 Stage Prompt + 声明的输入产物。
 *
 * <p>不注入完整聊天历史、其他步骤的内部日志或无关工具结果。
 */
@Slf4j
@Component
public class DefaultStepContextBuilder implements StepContextBuilder {

    private static final String DEFAULT_SKILL = "agent-writing";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SkillLoader skillLoader;
    private final StagePromptRegistry stagePromptRegistry;
    private final ToolPolicyRegistry toolPolicyRegistry;
    private final ObjectMapper objectMapper;

    public DefaultStepContextBuilder(SkillLoader skillLoader,
                                     StagePromptRegistry stagePromptRegistry,
                                     ToolPolicyRegistry toolPolicyRegistry,
                                     ObjectMapper objectMapper) {
        this.skillLoader = skillLoader;
        this.stagePromptRegistry = stagePromptRegistry;
        this.toolPolicyRegistry = toolPolicyRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public StepContext build(TaskRun task, StepDefinition step, WorkflowDefinition workflow,
                             List<Artifact> inputs, UserInput userInput) {
        String skillName = task != null && task.getContext() != null
                && task.getContext().get("skill") instanceof String s
                ? s : (workflow != null && workflow.getSkill() != null ? workflow.getSkill() : DEFAULT_SKILL);

        String skillBody = renderSkill(skillName, task);
        String stagePrompt = stagePromptRegistry.load(step.getPrompt()).orElse("");

        StringBuilder system = new StringBuilder();
        if (skillBody != null && !skillBody.isBlank()) {
            system.append(skillBody).append("\n\n");
        }
        if (!stagePrompt.isBlank()) {
            system.append(stagePrompt);
        }

        StringBuilder user = new StringBuilder();
        if (task != null && task.getGoal() != null && !task.getGoal().isBlank()) {
            user.append("## 用户要求\n").append(task.getGoal()).append("\n\n");
        }
        if (inputs != null && !inputs.isEmpty()) {
            user.append("## 当前阶段可用材料\n");
            for (Artifact artifact : inputs) {
                user.append("### ").append(artifact.type().name()).append("\n")
                        .append(toJson(artifact.content())).append("\n\n");
            }
        }
        if (userInput != null && userInput.content() != null && !userInput.content().isBlank()) {
            user.append("## 用户补充输入\n").append(userInput.content()).append("\n");
        }

        Set<String> allowedTools = toolPolicyRegistry.resolveAllowedTools(workflow, step.getToolPolicy());

        Map<String, Object> variables = new HashMap<>();
        if (task != null) {
            variables.put("taskRunId", task.getTaskRunId());
        }
        variables.put("allowedTools", allowedTools);

        return new StepContext(system.toString(), user.toString(), allowedTools, variables);
    }

    private String renderSkill(String skillName, TaskRun task) {
        Optional<SkillManifest> manifest = skillLoader.load(skillName);
        if (manifest.isEmpty()) {
            log.warn("[STEP-CTX] skill not found: {}, using empty", skillName);
            return "";
        }
        String template = manifest.get().getSystemPromptTemplate();
        if (template == null || template.isBlank()) {
            return "";
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("currentTime", LocalDateTime.now().format(TIME_FORMAT));
        if (task != null && task.getContext() != null) {
            vars.putAll(task.getContext());
        }
        String rendered = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            rendered = rendered.replace("{{" + e.getKey() + "}}",
                    e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return rendered;
    }

    private String toJson(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }
}
