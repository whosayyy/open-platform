package com.yin.ai.platform.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基座 Agent — ReAct 循环的最小化抽象。
 *
 * <p>职责：将原始 {@link AgentTask} 转化为"已配置"的 AgentTask，
 * 注入系统提示词、工具白名单、模型参数等。实际的 ReAct 循环仍由
 * {@link com.yin.ai.platform.agent.runtime.DefaultAgentRuntime} 驱动。
 *
 * <p>设计原则：
 * <ul>
 *   <li>ai-platform 是技术基座 — 只关心 ReAct 循环、流式输出、工具调用</li>
 *   <li>业务层（wkc-ai-service）继承本类注入领域知识（品牌记忆、平台规则、用户风格）</li>
 *   <li>子类通过覆写模板方法定制行为，不修改 ReActLoop / DefaultAgentExecutor</li>
 * </ul>
 *
 * <p>扩展点（子类可覆写的方法）：
 * <ul>
 *   <li>{@link #getAgentType()} — Agent 类型标识（必填）</li>
 *   <li>{@link #buildSystemPrompt(Map)} — 系统提示词（必填）</li>
 *   <li>{@link #getToolWhitelist()} — 工具白名单（null=不限制）</li>
 *   <li>{@link #getAgentConfig()} — AgentConfig（maxSteps/timeout 等）</li>
 *   <li>{@link #getTemperature()} — 模型温度（null=executor 默认 0.3）</li>
 *   <li>{@link #getModel()} — 模型名称（null=默认模型）</li>
 *   <li>{@link #enrichContext(Map)} — 额外上下文注入</li>
 * </ul>
 *
 * @since 多 Agent 架构 V1
 */
public abstract class BaseReactAgent {

    /**
     * Agent 类型标识 — 用于路由、日志、AgentTaskService 持久化。
     *
     * @return 唯一类型名，如 "creator", "research", "writing"
     */
    public abstract String getAgentType();

    /**
     * 构建系统提示词 — Agent 的角色定义和行为规则。
     *
     * <p>调用时机：{@link #configure(AgentTask)} 执行时，将返回值注入
     * 上下文变量 {@code systemPrompt}，由 DefaultAgentExecutor.buildMessages()
     * 读取并作为 LLM 的 system message。
     *
     * @param context 当前任务上下文（含前端传入的 userId、caller 等）
     * @return 完整的系统提示词文本
     */
    protected abstract String buildSystemPrompt(Map<String, Object> context);

    /**
     * 工具白名单 — null 表示不限制，使用 FunctionRegistry 全部已注册工具。
     *
     * <p>返回的 Set 会被注入到 AgentTask.toolFilter()，
     * DefaultAgentExecutor.executeTool() 据此过滤非法工具调用。
     */
    protected Set<String> getToolWhitelist() {
        return null;
    }

    /**
     * Agent 配置 — maxSteps、timeout、planningMode 等。
     * 默认使用 {@link AgentConfig#defaults()}（15 步、10 分钟超时）。
     */
    protected AgentConfig getAgentConfig() {
        return AgentConfig.defaults();
    }

    /**
     * 模型温度 — null 使用 DefaultAgentExecutor 默认值 0.3。
     *
     * <p>建议值：
     * <ul>
     *   <li>搜索/分析类 Agent：0.2（严谨、确定性高）</li>
     *   <li>写作/创意类 Agent：0.7（创造性更强）</li>
     *   <li>reasoner 模型：返回 null（executor 自动跳过 temperature）</li>
     * </ul>
     */
    protected Double getTemperature() {
        return null;
    }

    /**
     * 模型名称 — null 使用 Gateway 默认模型。
     * 例如 "deepseek-chat", "deepseek-reasoner"。
     */
    protected String getModel() {
        return null;
    }

    /**
     * 额外上下文注入 — 子类可覆写以添加自定义变量。
     *
     * <p>调用时机：在 systemPrompt、model、temperature 注入之后，
     * 构建最终 AgentTask 之前。
     *
     * @param context 可修改的上下文 Map
     */
    protected void enrichContext(Map<String, Object> context) {
        // 默认空实现
    }

    /**
     * 将原始 AgentTask 转化为已配置的 AgentTask。
     *
     * <p>这是 AgentRouter 调用的唯一入口。注入顺序：
     * <ol>
     *   <li>systemPrompt（来自 {@link #buildSystemPrompt(Map)}）</li>
     *   <li>model（来自 {@link #getModel()}，null 则不注入）</li>
     *   <li>temperature（来自 {@link #getTemperature()}，null 则不注入）</li>
     *   <li>agentType（来自 {@link #getAgentType()}）</li>
     *   <li>子类额外注入（{@link #enrichContext(Map)}）</li>
     * </ol>
     *
     * <p>优先级规则：前端通过 AgentTask 传入的字段优先级最高，
     * Agent 默认值仅在字段缺失时生效。
     *
     * @param raw 前端提交的原始 AgentTask
     * @return 配置完成的 AgentTask
     */
    public AgentTask configure(AgentTask raw) {
        Map<String, Object> ctx = new HashMap<>(raw.context() != null ? raw.context() : Map.of());

        // 1. 系统提示词（Agent 默认，前端一般不覆盖）
        if (!ctx.containsKey("systemPrompt")) {
            ctx.put("systemPrompt", buildSystemPrompt(ctx));
        }

        // 2. 模型名称（前端指定优先，否则用 Agent 默认）
        if (!ctx.containsKey("model")) {
            String model = getModel();
            if (model != null) {
                ctx.put("model", model);
            }
        }

        // 3. 温度（前端指定优先，否则用 Agent 默认）
        if (!ctx.containsKey("temperature")) {
            Double temperature = getTemperature();
            if (temperature != null) {
                ctx.put("temperature", temperature);
            }
        }

        // 4. Agent 类型标识（始终注入，用于日志和持久化）
        ctx.put("agentType", getAgentType());

        // 5. 子类额外注入
        enrichContext(ctx);

        // 6. 工具白名单（前端 toolFilter 优先，否则用 Agent 默认）
        Set<String> tools = raw.toolFilter() != null ? raw.toolFilter() : getToolWhitelist();

        // 7. Agent 配置（前端 config 优先，否则用 Agent 默认）
        AgentConfig config = raw.config() != null ? raw.config() : getAgentConfig();

        return new AgentTask(raw.goal(), ctx, tools, config);
    }
}
