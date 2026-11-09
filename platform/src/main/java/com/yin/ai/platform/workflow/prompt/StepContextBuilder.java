package com.yin.ai.platform.workflow.prompt;

import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.UserInput;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;

import java.util.List;

/**
 * 步骤级上下文构建器 — 为不同阶段只注入必要内容，禁止默认注入完整聊天历史。
 */
public interface StepContextBuilder {

    /**
     * 构建步骤提示词。
     *
     * @param task      任务运行实例
     * @param step      步骤定义
     * @param workflow  工作流定义
     * @param inputs    已解析的输入产物
     * @param userInput WAITING_INPUT 恢复时的补充输入（可为 null）
     */
    StepContext build(TaskRun task, StepDefinition step, WorkflowDefinition workflow,
                      List<Artifact> inputs, UserInput userInput);
}
