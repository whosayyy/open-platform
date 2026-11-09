package com.yin.ai.platform.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义 AI Agent 注解。
 * 标注在 Agent 定义类上，由统一 Agent Runtime 读取元数据并经 AI Gateway 执行。
 *
 * <pre>{@code
 * @AiAgent(
 *     name = "collector",
 *     description = "热点新闻采集 Agent",
 *     systemPrompt = "你是一名专业的助手...",
 *     tools = WebSearchTools.class,
 *     temperature = 0.3
 * )
 * @Component
 * public class MyAgent {}
 *
 * // 使用时将定义注册到统一 Agent Runtime，不直接创建模型客户端。
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiAgent {

    /**
     * Agent 唯一名称（ReactAgent 使用）。默认取类简单名。
     */
    String name() default "";

    /**
     * Agent 能力描述（ReactAgent 使用）。默认取 "AI Agent: {className}"。
     */
    String description() default "";

    /**
     * Agent 的系统提示词（System Prompt），定义 Agent 的角色和行为。
     */
    String systemPrompt();

    /**
     * Agent 可调用的工具类（@Component + @Tool 方法）。
     */
    Class<?>[] tools() default {};

    /**
     * 模型温度（0.0 ~ 1.0），越低越严谨，越高越有创造力。
     */
    double temperature() default 0.7;
}
