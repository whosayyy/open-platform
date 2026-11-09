package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.function.FunctionDefinition;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Planner Prompt 策略 SPI — 控制 LLMPlanner 发送给 LLM 的 System/User Prompt。
 * <p>
 * 默认实现提供通用的 JSON 步骤规划 Prompt。业务方可注入自定义策略实现：
 * <ul>
 *   <li>A/B 测试不同 Prompt 版本</li>
 *   <li>国际化 Prompt 模板</li>
 *   <li>领域特定的步骤规划指令</li>
 * </ul>
 */
public interface PlannerPromptStrategy {

    String buildSystemPrompt();

    String buildUserMessage(String goal, Collection<FunctionDefinition> availableTools);

    /** 默认策略 — 通用 JSON 步骤规划 Prompt。 */
    static PlannerPromptStrategy defaults() {
        return Default.INSTANCE;
    }

    final class Default implements PlannerPromptStrategy {
        static final Default INSTANCE = new Default();

        private Default() {}

        @Override
        public String buildSystemPrompt() {
            return """
                    你是一个任务规划器。给定用户目标和可用工具列表，将任务拆解为 2-7 个有序执行步骤。

                    输出 JSON 格式（仅输出 JSON，不要其他文字）：
                    {
                      "steps": [
                        {
                          "id": "step-1",
                          "description": "步骤描述",
                          "tool": "工具名或null",
                          "toolParams": {"参数名": "参数值"},
                          "dependsOn": ["前置步骤id"]
                        }
                      ]
                    }

                    规则：
                    - 每个步骤的 id 必须唯一
                    - tool 字段：如果需要调用指定工具则填工具名，如果是 LLM 直接处理则填 null
                    - toolParams：工具调用参数，非工具步骤填 {}
                    - dependsOn：列出此步骤依赖的前置步骤 id 列表，无依赖则为空数组 []
                    - 步骤数 2-7 个，按执行顺序排列
                    - 简单任务（只需一步或无法拆解）返回 {"direct": "直接回答内容"}
                    """;
        }

        @Override
        public String buildUserMessage(String goal, Collection<FunctionDefinition> availableTools) {
            return buildToolSection(availableTools) + "\n用户目标：" + goal;
        }

        private static String buildToolSection(Collection<FunctionDefinition> definitions) {
            if (definitions == null || definitions.isEmpty()) {
                return "可用工具：无（所有步骤由 LLM 直接处理）";
            }
            String toolList = definitions.stream()
                    .map(d -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append("- ").append(d.getName()).append("：")
                          .append(d.getDescription() != null ? d.getDescription() : "");
                        if (d.getParameters() != null && !d.getParameters().isEmpty()) {
                            sb.append("\n  参数：");
                            for (var p : d.getParameters()) {
                                sb.append("\n    - ").append(p.getName())
                                  .append(" (").append(p.getType())
                                  .append(p.isRequired() ? ", 必填" : ", 可选")
                                  .append(")：").append(p.getDescription() != null ? p.getDescription() : "");
                            }
                        }
                        return sb.toString();
                    })
                    .collect(Collectors.joining("\n"));
            return "可用工具：\n" + toolList;
        }
    }
}
