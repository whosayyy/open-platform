package com.yin.ai.platform.core.memory.procedural;

/**
 * Procedure 步骤类型 — 描述任务执行中的认知/操作阶段。
 * <p>
 * 对应 ReAct (Reasoning + Acting) 循环的各个阶段：
 * <ul>
 *   <li>{@link #THINK} — 推理/规划</li>
 *   <li>{@link #TOOL_CALL} — 工具调用</li>
 *   <li>{@link #OBSERVE} — 观察结果</li>
 *   <li>{@link #DECIDE} — 决策分支</li>
 *   <li>{@link #ACT} — 执行动作（非工具，如代码生成、消息发送）</li>
 * </ul>
 */
public enum ProcedureStepType {
    THINK,
    TOOL_CALL,
    OBSERVE,
    DECIDE,
    ACT
}
