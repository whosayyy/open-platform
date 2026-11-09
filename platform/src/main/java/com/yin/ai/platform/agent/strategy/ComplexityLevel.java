package com.yin.ai.platform.agent.strategy;

/**
 * 任务复杂度等级。
 */
public enum ComplexityLevel {

    /** 单轮即可完成（如"今天天气怎么样"） */
    SIMPLE,

    /** 需要多步推理但线性（如"帮我对比 A 和 B"） */
    MODERATE,

    /** 需要自主规划 + 多轮反思（如"设计一个方案解决 X"） */
    COMPLEX
}
