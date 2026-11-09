package com.yin.ai.platform.workflow.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流步骤定义 — 声明依赖、输入/输出产物、执行器、Stage Prompt、Tool Policy 与重试。
 */
public class StepDefinition {

    private String id;
    private String name;
    private ExecutorType executorType = ExecutorType.LLM;
    /** Stage Prompt 引用，如 "writing/brief"，由 StagePromptRegistry 解析 */
    private String prompt;
    /** 前置步骤 ID 列表 */
    private List<String> dependsOn = new ArrayList<>();
    /** 输入产物类型名列表（ArtifactType.name()） */
    private List<String> input = new ArrayList<>();
    /** 输出产物类型名（ArtifactType.name()），null 表示不产出 */
    private String output;
    /** Tool Policy 名称（可选，为空则无工具） */
    private String toolPolicy;
    /** APPLICATION 步骤的 Handler Bean 名 */
    private String handler;
    /** 条件（可选） */
    private StepCondition condition;
    /** 重试策略 */
    private RetryPolicy retry = new RetryPolicy();
    /** 超时秒数 */
    private int timeoutSeconds = 300;

    public StepDefinition() {
    }

    public List<ArtifactType> inputTypes() {
        List<ArtifactType> types = new ArrayList<>();
        for (String name : input) {
            types.add(ArtifactType.from(name));
        }
        return types;
    }

    public ArtifactType outputType() {
        return output == null || output.isBlank() ? null : ArtifactType.from(output);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ExecutorType getExecutorType() {
        return executorType;
    }

    public void setExecutorType(ExecutorType executorType) {
        this.executorType = executorType;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn == null ? new ArrayList<>() : dependsOn;
    }

    public List<String> getInput() {
        return input;
    }

    public void setInput(List<String> input) {
        this.input = input == null ? new ArrayList<>() : input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getToolPolicy() {
        return toolPolicy;
    }

    public void setToolPolicy(String toolPolicy) {
        this.toolPolicy = toolPolicy;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public StepCondition getCondition() {
        return condition;
    }

    public void setCondition(StepCondition condition) {
        this.condition = condition;
    }

    public RetryPolicy getRetry() {
        return retry;
    }

    public void setRetry(RetryPolicy retry) {
        this.retry = retry == null ? new RetryPolicy() : retry;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
