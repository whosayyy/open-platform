package com.yin.ai.platform.agent.planner;

/**
 * 规划模式。
 */
public enum PlanningMode {
    /** 使用预定义步骤模板（确定性） */
    TEMPLATE,
    /** LLM 自主生成计划（开放性任务） */
    LLM,
    /** 模板框架 + LLM 增强（预留） */
    HYBRID
}
