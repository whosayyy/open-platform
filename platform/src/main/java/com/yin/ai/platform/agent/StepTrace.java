package com.yin.ai.platform.agent;

/**
 * 单步执行轨迹。
 *
 * @param stepIndex    步骤索引
 * @param action       执行的动作描述
 * @param result       执行结果
 * @param observation  观察结果
 * @param decision     反思决策
 * @param retryCount   本步重试次数
 * @param durationMs   本步耗时（毫秒）
 */
public record StepTrace(
        int stepIndex,
        String action,
        String result,
        String observation,
        String decision,
        int retryCount,
        long durationMs) {

    public static StepTrace of(int stepIndex, String action, String result, long durationMs) {
        return new StepTrace(stepIndex, action, result, null, null, 0, durationMs);
    }
}
