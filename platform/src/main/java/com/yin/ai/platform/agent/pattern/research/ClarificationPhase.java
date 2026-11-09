package com.yin.ai.platform.agent.pattern.research;

import com.yin.ai.platform.core.runtime.research.ClarificationResult;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.ReasoningOptionsSupport;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.gateway.GatewayTextClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 需求澄清阶段 — DeepResearch 的第一步。
 * <p>
 * 核心设计：由 LLM 主导判断，而非规则硬编码。
 * <ul>
 *   <li>LLM 先尝试自行理解和补全研究范围（推断意图、填充合理默认值）</li>
 *   <li>只有 LLM 确实无法判断用户意图时，才生成问题让用户选择</li>
 *   <li>大多数情况下 LLM 能直接输出 clarifiedGoal，无需打断用户</li>
 * </ul>
 * <p>
 * 状态流转：
 * - LLM 能自行澄清 → 直接输出 clarifiedGoal，不进入 WAITING
 * - LLM 无法判断 → 生成问题 → WAITING → 用户回答 → mergeAnswers
 */
public class ClarificationPhase {

    private static final Logger log = LoggerFactory.getLogger(ClarificationPhase.class);

    private final GatewayTextClient chatClient;

    public ClarificationPhase(GatewayTextClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 执行需求澄清（LLM 主导）。
     *
     * @param ctx 执行上下文
     * @return 澄清结果
     */
    public ClarificationResult execute(AgentExecutionContext ctx) {
        String goal = ctx.getGoal();

        String prompt = """
                你是一个研究需求分析器。分析用户的研究请求，判断你是否能自行确定完整的研究范围。

                用户请求：%s

                你的任务：
                1. 尝试推断用户的研究意图、范围、维度、时间范围、目标受众
                2. 如果你能合理推断（即使信息不完整，但可以做出合理默认假设），直接输出澄清后的研究目标
                3. 只有当存在关键歧义（如：完全无法判断研究对象、存在多种截然不同的理解方向）时，才生成问题

                输出格式（严格 JSON）：
                - 如果你能自行澄清：{"action": "clarified", "goal": "完整的澄清后研究目标描述（2-3句话）", "assumptions": ["假设1", "假设2"]}
                - 如果需要用户确认：{"action": "ask", "questions": ["问题1", "问题2"], "options": {"问题1": ["推荐选项1", "推荐选项2"], "问题2": ["选项A", "选项B"]}, "reason": "为什么无法自行判断"}

                options 要求：每个问题给 2-4 个简洁的推荐选项（每个不超过 12 个字），
                让用户可以直接点击选择，也可以自行输入其他答案。

                判断原则：
                - 宁可做一个合理假设然后执行，也不要频繁打断用户
                - 只有"研究对象不明确"或"存在完全相反的理解方向"才需要问
                - "研究深度"、"输出格式"这类可以默认处理，不需要问
                """.formatted(goal);

        try {
            String response = callWithConfig(ctx, prompt);
            return parseClarificationResponse(response, goal, ctx);
        } catch (Exception e) {
            log.warn("[CLARIFY] LLM analysis failed, proceeding with original goal: {}", e.getMessage());
            return ClarificationResult.notNeeded(goal);
        }
    }

    /**
     * 处理用户回答，合并为 clarifiedGoal。
     * 由 UnifiedAgentRuntime.resume() 调用。
     */
    public String mergeAnswers(AgentExecutionContext ctx, Map<String, String> answers) {
        String originalGoal = ctx.getGoal();

        if (answers == null || answers.isEmpty()) {
            log.info("[CLARIFY] no answers provided, using original goal");
            return originalGoal;
        }

        String prompt = """
                将以下研究目标和用户的补充说明合并为一个精确的研究目标描述。

                原始目标：%s
                用户补充：
                %s

                输出：一段 2-3 句话的精确研究目标描述，包含具体范围、维度、约束。
                """.formatted(originalGoal, formatAnswers(answers));

        String clarified = callWithConfig(ctx, prompt);
        ctx.putVariable("clarifiedGoal", clarified);

        log.info("[CLARIFY] taskId={} goal clarified after user input", ctx.getTaskId());
        return clarified;
    }

    private String callWithConfig(AgentExecutionContext ctx, String userPrompt) {
        Object sessionContext = ctx.getVariable("sessionContext");
        if (sessionContext != null && !sessionContext.toString().isBlank()) {
            userPrompt = sessionContext + "\n\n" + userPrompt;
        }
        String sp = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
        Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
        var executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof com.yin.ai.platform.gateway.ModelExecutionOptions value ? value : null;
        return chatClient.generate("agent:clarification", userPrompt, sp, temp, executionOptions)
                .getContent();
    }

    private ClarificationResult parseClarificationResponse(String response, String originalGoal,
                                                            AgentExecutionContext ctx) {
        if (response == null) {
            return ClarificationResult.notNeeded(originalGoal);
        }

        String lower = response.toLowerCase();

        // LLM 自行澄清成功
        if (lower.contains("\"clarified\"") || lower.contains("clarified")) {
            String clarifiedGoal = extractJsonField(response, "goal");
            if (clarifiedGoal != null && !clarifiedGoal.isBlank()) {
                ctx.putVariable("clarifiedGoal", clarifiedGoal);
                ctx.putVariable("clarification.assumptions", extractJsonField(response, "assumptions"));
                log.info("[CLARIFY] LLM self-clarified: '{}'",
                        clarifiedGoal.substring(0, Math.min(80, clarifiedGoal.length())));
                return ClarificationResult.notNeeded(clarifiedGoal);
            }
        }

        // LLM 判断需要用户确认
        if (lower.contains("\"ask\"") || lower.contains("\"questions\"")) {
            List<String> questions = parseQuestions(response);
            if (!questions.isEmpty()) {
                // [HITL-UI] 解析每个问题的推荐选项（LLM 生成的 chips），为空时前端仅显示输入框
                Map<String, List<String>> options = parseOptions(response, questions);
                // 发射澄清事件
                ctx.getEventPublisher().publish(new AgentEvent(
                        null, ctx.getTaskId(), TaskEventType.TASK_WAITING, null,
                        Map.of("questions", questions, "options", options, "phase", "clarification"),
                        Instant.now()));

                ctx.transitionTo(TaskState.WAITING);
                ctx.putVariable("clarification.questions", questions);
                ctx.putVariable("clarification.options", options);

                log.info("[CLARIFY] LLM decided to ask {} questions, {} with options",
                        questions.size(), options.size());
                return ClarificationResult.waiting(questions, options);
            }
        }

        // 解析失败 → 默认不澄清
        return ClarificationResult.notNeeded(originalGoal);
    }

    /**
     * [HITL-UI] 解析每个问题的推荐选项。
     * 期望格式：{"options": {"问题1": ["选项A","选项B"], "问题2": [...]}}
     * 解析失败或格式不符时返回空 Map（不影响主流程，前端降级为纯输入框）。
     */
    private Map<String, List<String>> parseOptions(String response, List<String> questions) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        try {
            int idx = response.indexOf("\"options\"");
            if (idx < 0) return result;
            int objStart = response.indexOf("{", idx);
            if (objStart < 0) return result;

            // 括号深度扫描取出 options 对象
            int depth = 0;
            boolean inString = false;
            int objEnd = -1;
            for (int i = objStart; i < response.length(); i++) {
                char c = response.charAt(i);
                if (inString) {
                    if (c == '\\') i++;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { objEnd = i; break; }
            }
            if (objEnd < 0) return result;

            String objJson = response.substring(objStart, objEnd + 1);
            // 逐问题匹配："问题": ["a","b"]
            for (String q : questions) {
                int qIdx = objJson.indexOf("\"" + escapeJson(q) + "\"");
                if (qIdx < 0) {
                    // 前缀匹配（LLM 可能微调问题文本）：按顺序取第一个未消费的数组
                    continue;
                }
                int arrStart = objJson.indexOf("[", qIdx);
                if (arrStart < 0) continue;
                int arrEnd = objJson.indexOf("]", arrStart);
                if (arrEnd < 0) continue;
                String arrContent = objJson.substring(arrStart + 1, arrEnd);
                List<String> opts = java.util.Arrays.stream(arrContent.split(","))
                        .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                        .filter(s -> !s.isBlank())
                        .limit(4)
                        .toList();
                if (!opts.isEmpty()) {
                    result.put(q, opts);
                }
            }
        } catch (Exception e) {
            log.warn("[CLARIFY] parse options failed (non-fatal): {}", e.getMessage());
        }
        return result;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<String> parseQuestions(String response) {
        try {
            // 提取 "questions": [...] 中的内容
            int idx = response.indexOf("\"questions\"");
            if (idx < 0) return List.of();
            int arrStart = response.indexOf("[", idx);
            int arrEnd = response.indexOf("]", arrStart);
            if (arrStart < 0 || arrEnd < 0) return List.of();

            String arrContent = response.substring(arrStart + 1, arrEnd);
            return java.util.Arrays.stream(arrContent.split(","))
                    .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                    .filter(s -> !s.isBlank())
                    .limit(4)
                    .toList();
        } catch (Exception e) {
            log.warn("[CLARIFY] parse questions failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonField(String json, String field) {
        try {
            int idx = json.indexOf("\"" + field + "\"");
            if (idx < 0) return null;
            int colonIdx = json.indexOf(":", idx);
            if (colonIdx < 0) return null;

            // 跳过空白
            int start = colonIdx + 1;
            while (start < json.length() && json.charAt(start) == ' ') start++;

            if (start >= json.length()) return null;

            if (json.charAt(start) == '"') {
                // 字符串值
                int end = json.indexOf("\"", start + 1);
                return end > start ? json.substring(start + 1, end) : null;
            } else if (json.charAt(start) == '[') {
                // 数组值（如 assumptions）
                int end = json.indexOf("]", start);
                return end > start ? json.substring(start, end + 1) : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String formatAnswers(Map<String, String> answers) {
        StringBuilder sb = new StringBuilder();
        answers.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }
}
