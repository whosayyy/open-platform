package com.yin.ai.platform.agent.reflector;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.observer.Observation;

import java.util.List;

/**
 * Reflector Prompt 策略 SPI — 控制 LLMReflector 发送给 LLM 的决策 Prompt。
 * <p>
 * 默认实现提供通用的反思决策 Prompt。业务方可注入自定义策略实现 A/B 测试或领域定制。
 */
public interface ReflectorPromptStrategy {

    /**
     * 构建反思决策 Prompt。
     *
     * @param observation 当前步骤的观察结论
     * @param ctx         Agent 执行上下文
     * @return Prompt 文本
     */
    String buildReflectPrompt(Observation observation, AgentContext ctx);

    /** 默认策略 — 通用反思决策 Prompt。 */
    static ReflectorPromptStrategy defaults() {
        return Default.INSTANCE;
    }

    final class Default implements ReflectorPromptStrategy {
        static final Default INSTANCE = new Default();

        private Default() {}

        @Override
        public String buildReflectPrompt(Observation observation, AgentContext ctx) {
            List<String> traceSummaries = ctx.getTrace().stream()
                    .filter(e -> e.data() != null && !e.data().isBlank())
                    .map(e -> "- step " + e.stepIndex() + " [" + e.type().name() + "]: "
                            + e.data())
                    .toList();

            return """
                    你是一个任务执行反思器。根据以下信息决定下一步行动。

                    用户目标：%s
                    当前步骤：%d
                    已完成步骤：
                    %s
                    当前观察：%s
                    观察状态：%s

                    请仅输出以下三个单词之一（不要其他文字）：
                    CONTINUE — 继续执行下一步
                    RETRY — 重新执行当前步骤
                    COMPLETE — 任务已完成，可以结束
                    """.formatted(
                    ctx.getTask().goal(),
                    ctx.getCurrentStep(),
                    traceSummaries.isEmpty() ? "无" : String.join("\n", traceSummaries),
                    observation.summary(),
                    observation.status().name());
        }
    }
}
