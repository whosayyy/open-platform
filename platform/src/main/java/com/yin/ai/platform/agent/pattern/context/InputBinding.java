package com.yin.ai.platform.agent.pattern.context;

import com.yin.ai.platform.core.memory.MemoryType;

/**
 * 步骤输入绑定 — 定义当前步骤的某个输入从哪里来、如何解析。
 * <p>
 * 两种来源：
 * 1. 前序步骤输出（sourceStepId != null）
 * 2. Memory 检索（memoryType != null）
 *
 * @param key          输入变量名（注入 Prompt 时的占位符名）
 * @param sourceStepId 来源步骤 ID（null 表示非步骤来源）
 * @param mode         解析模式
 * @param memoryType   记忆类型（仅 MEMORY 模式使用）
 * @param memoryQuery  记忆检索查询模板（支持 {goal} 变量）
 * @param memoryTopK   记忆检索数量
 */
public record InputBinding(
        String key,
        String sourceStepId,
        ResolutionMode mode,
        MemoryType memoryType,
        String memoryQuery,
        int memoryTopK
) {
    /** 从前序步骤内联 */
    public static InputBinding inline(String key, String sourceStepId) {
        return new InputBinding(key, sourceStepId, ResolutionMode.INLINE, null, null, 0);
    }

    /** 从前序步骤摘要 */
    public static InputBinding summary(String key, String sourceStepId) {
        return new InputBinding(key, sourceStepId, ResolutionMode.SUMMARY, null, null, 0);
    }

    /** 从前序步骤引用 */
    public static InputBinding reference(String key, String sourceStepId) {
        return new InputBinding(key, sourceStepId, ResolutionMode.REFERENCE, null, null, 0);
    }

    /** 从 Memory 检索 */
    public static InputBinding memory(String key, MemoryType type, String query, int topK) {
        return new InputBinding(key, null, ResolutionMode.MEMORY, type, query, topK);
    }
}
