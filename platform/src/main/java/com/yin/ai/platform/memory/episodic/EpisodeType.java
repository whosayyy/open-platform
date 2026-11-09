package com.yin.ai.platform.memory.episodic;

/**
 * Episode 类型 — 描述记忆片段的分组语义。
 */
public enum EpisodeType {
    /** 对话片段（user ↔ assistant） */
    CONVERSATION,
    /** 任务执行片段 */
    TASK,
    /** 工作流步骤片段 */
    WORKFLOW_STEP,
    /** 自定义片段 */
    CUSTOM
}
