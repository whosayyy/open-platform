package com.yin.ai.platform.agent.pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * LLM 模式分类器 — 规则链全部无法判断时兜底，用 LLM 决策执行模式。
 * <p>
 * 触发场景：输入同时命中多个规则（如"写一份市场分析报告"既含创作又含调研关键词），
 * 规则分类器返回 empty，本分类器让 LLM 从 SIMPLE/REACT/PLAN_EXECUTE/DEEP_RESEARCH 中决策。
 * 任何失败（LLM 异常、解析失败）都返回 empty，由 DefaultAgentRouter 回退到 REACT。
 */
public class LlmModeClassifier implements ModeClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmModeClassifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_NAME = "submitModeDecision";

    private static final String TOOL_DESCRIPTION =
            "提交用户输入的 Agent 执行模式决策。完成判断后必须调用本工具，把 mode/reason 作为工具参数返回，不要输出任何文字。";

    private static final Map<String, Object> SCHEMA = readSchema("""
            {"type":"object","properties":{
              "mode":{"type":"string",
                "description":"SIMPLE=简单问答一次调用即可 / REACT=需调用工具的多步任务 / PLAN_EXECUTE=多步创作或方案制定 / DEEP_RESEARCH=需联网深度调研",
                "enum":["SIMPLE","REACT","PLAN_EXECUTE","DEEP_RESEARCH"]},
              "reason":{"type":"string","description":"决策理由"}},
             "required":["mode"]}
            """);

    private final StructuredClient structuredClient;

    public LlmModeClassifier(StructuredClient structuredClient) {
        this.structuredClient = structuredClient;
    }

    @Override
    public Optional<AgentMode> classify(String userInput, RouteContext ctx) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.of(AgentMode.DIRECT);
        }
        try {
            String args = structuredClient.generate(
                    TOOL_NAME, TOOL_DESCRIPTION, SCHEMA,
                    buildPrompt(userInput), null, 0.2).orElse(null);
            return parse(args);
        } catch (Exception e) {
            log.warn("[ROUTER] LLM mode classification failed, deferring: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(String input) {
        return """
                你是 Agent 路由决策器。根据用户输入判断应采用哪种执行模式。

                用户输入：%s

                模式说明：
                - SIMPLE：简单问答，一次 LLM 调用即可回答
                - REACT：需要调用工具的多步任务（如搜索/计算/翻译等）
                - PLAN_EXECUTE：多步创作/方案制定（写文章、出方案、做设计、规划）
                - DEEP_RESEARCH：需要联网深度调研（市场分析、行业研究、综述、对比评估）

                完成判断后必须调用 submitModeDecision 工具提交决策（工具参数即为结果 JSON），不要输出任何文字。
                """.formatted(input);
    }

    private Optional<AgentMode> parse(String args) {
        if (args == null || args.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(args);
            String mode = node.path("mode").asText("");
            if (mode.isBlank()) {
                return Optional.empty();
            }
            AgentMode decided = AgentMode.fromString(mode);
            log.info("[ROUTER] LLM classifier decided {} for '{}'", decided, mode);
            return Optional.of(decided);
        } catch (Exception e) {
            log.warn("[ROUTER] parse LLM mode decision failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> readSchema(String jsonSchema) {
        try {
            return MAPPER.readValue(jsonSchema, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON schema constant", e);
        }
    }

    @Override
    public int priority() {
        return 1000;  // 最后兜底：规则链全部无法判断时才调用 LLM
    }
}
