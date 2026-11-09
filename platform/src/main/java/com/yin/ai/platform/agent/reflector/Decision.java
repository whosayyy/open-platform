package com.yin.ai.platform.agent.reflector;

/**
 * Agent 反思决策。
 */
public enum Decision {
    /** 继续执行下一步 */
    CONTINUE,
    /** 重新执行当前步骤 */
    RETRY,
    /** 任务完成 */
    COMPLETE
}
