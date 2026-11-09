package com.yin.ai.platform.memory.policy;

/**
 * Consolidation 范围 — 控制从 Episodic 中提取的知识类型。
 */
public enum ConsolidationScope {
    /** 仅提取 Episode */
    EPISODE,
    /** 仅提取 Fact */
    FACT,
    /** 仅学习 Procedure */
    PROCEDURE,
    /** 全部类型 */
    ALL
}
