package com.yin.ai.platform.core.memory.working;

/**
 * PlanNode 类型 — 定义 Agent 执行计划树中的节点角色。
 * GOAL:     顶层目标
 * SUBGOAL:  子目标（可分解）
 * ACTION:   具体动作（调用 Tool/LLM）
 * CONDITION: 条件分支
 * LOOP:     循环节点
 * PARALLEL: 并行节点（多 Action 同时执行）
 */
public enum PlanNodeType {
    GOAL,
    SUBGOAL,
    ACTION,
    CONDITION,
    LOOP,
    PARALLEL
}
