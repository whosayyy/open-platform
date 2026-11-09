package com.yin.ai.platform.agent.pattern.context;

import java.util.List;

/**
 * 步骤契约 — 定义一个步骤的输入来源和输出标识。
 * <p>
 * 由 Planner 在生成计划时为每步指定，ContextManager 在执行前解析。
 * 这是步骤间数据传递的核心抽象：步骤不直接读前序输出，
 * 而是通过契约声明"我需要什么"，由 ContextManager 负责组装。
 *
 * @param stepId  步骤 ID
 * @param inputs  输入绑定列表
 * @param outputKey 输出变量名（写入 ContextStore 的 key）
 */
public record StepContract(
        String stepId,
        List<InputBinding> inputs,
        String outputKey
) {
    public static StepContract of(String stepId, String outputKey, InputBinding... inputs) {
        return new StepContract(stepId, List.of(inputs), outputKey);
    }

    /** 无输入契约（首步或独立步骤） */
    public static StepContract noInput(String stepId, String outputKey) {
        return new StepContract(stepId, List.of(), outputKey);
    }
}
