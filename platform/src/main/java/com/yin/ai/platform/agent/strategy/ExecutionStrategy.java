package com.yin.ai.platform.agent.strategy;

/**
 * Agent 执行策略 — 决定请求走哪条执行路径。
 */
public enum ExecutionStrategy {

    /** 简单 Skill 路径（单轮 LLM 调用，不走 ReAct Loop） */
    DIRECT_SKILL,

    /** ReAct Loop 路径（Plan → Act → Observe → Reflect） */
    REACT_LOOP,

    /** V5 预留：DAG 工作流路径 */
    WORKFLOW_DAG
}
