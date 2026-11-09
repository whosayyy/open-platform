package com.yin.ai.platform.runtime;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Skill 执行上下文。
 */
@Data
@Builder
public class SkillExecutionContext {

    /** 链路追踪 ID */
    private String traceId;

    /** 用户原始输入 */
    private String userInput;

    /** 会话 ID（Chat 场景使用） */
    private String sessionId;

    /** 用户 ID */
    private Long userId;

    /** 调用参数（如 contentId） */
    @Builder.Default
    private Map<String, Object> parameters = Map.of();

    /** 扩展属性 */
    @Builder.Default
    private Map<String, Object> attributes = Map.of();

    /** 跳过工具注册的标记键（用于 review/summary 等不需要工具调用的场景） */
    public static final String ATTR_SKIP_TOOLS = "_skipTools";
}
