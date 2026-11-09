package com.yin.ai.platform.memory.consolidation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.semantic.Fact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM 驱动的用户画像 Consolidator — 从用户的所有 Facts 中总结用户画像。
 * <p>
 * 输入：用户累积的全部 Fact（跨会话），输出：结构化用户画像
 * <ul>
 *   <li>身份信息（我是谁）</li>
 *   <li>偏好（喜欢什么、不喜欢什么）</li>
 *   <li>行为模式（习惯、节奏、关注领域）</li>
 *   <li>关键事件（重要决策、里程碑）</li>
 * </ul>
 * <p>
 * 每次触发时接收增量 Facts，返回完整画像（含增量更新标记）。
 * 不是直接操作 Memory 存储，而是生成结构化画像供上层存储。
 */
public class LlmUserProfileConsolidator {

    private static final Logger log = LoggerFactory.getLogger(LlmUserProfileConsolidator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 用户画像工具契约：{identity, preferences, behavior, keyFacts[], changes[]} */
    private static final Map<String, Object> PROFILE_SCHEMA = readSchema("""
            {"type":"object","properties":{
              "identity":{"type":"object","properties":{
                "role":{"type":"string","description":"角色/职业"},
                "expertise":{"type":"array","description":"擅长领域","items":{"type":"string"}}}},
              "preferences":{"type":"object","properties":{
                "likes":{"type":"array","description":"偏好","items":{"type":"string"}},
                "dislikes":{"type":"array","description":"不喜欢","items":{"type":"string"}}}},
              "behavior":{"type":"object","properties":{
                "activeHours":{"type":"string","description":"活跃时段"},
                "interactionStyle":{"type":"string","description":"交互风格"},
                "topicsOfInterest":{"type":"array","description":"关注话题","items":{"type":"string"}}}},
              "keyFacts":{"type":"array","description":"关键事实","items":{"type":"object","properties":{
                "fact":{"type":"string","description":"事实描述"},
                "confidence":{"type":"number","description":"置信度(0-1)"}}}},
              "changes":{"type":"array","description":"本次更新的内容项","items":{"type":"string"}}},
             "required":["identity","preferences","behavior","keyFacts","changes"]}
            """);

    private static final String PROFILE_TOOL_NAME = "submitProfile";

    private static final String PROFILE_TOOL_DESCRIPTION =
            "提交用户画像分析结果。完成分析后必须调用本工具，把画像对象作为工具参数返回，不要输出任何文字。";

    private static Map<String, Object> readSchema(String jsonSchema) {
        try {
            return JSON.readValue(jsonSchema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON schema constant", e);
        }
    }

    private final ModelAdapter modelAdapter;
    private final String model;

    public LlmUserProfileConsolidator(ModelAdapter modelAdapter) {
        this(modelAdapter, "deepseek-chat");
    }

    public LlmUserProfileConsolidator(ModelAdapter modelAdapter, String model) {
        this.modelAdapter = modelAdapter;
        this.model = model;
    }

    /**
     * 从 Facts 列表中总结用户画像。
     *
     * @param userId       用户 ID
     * @param facts        用户的所有 Facts（包含新旧）
     * @param existingProfile 已有画像内容（null 表示首次生成）
     * @return 用户画像 Map
     */
    public UserProfile consolidate(String userId, List<Fact> facts, String existingProfile) {
        if (facts == null || facts.isEmpty()) {
            return UserProfile.empty(userId);
        }

        String factsText = facts.stream()
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .map(f -> String.format("- %s %s %s (置信度: %.0f%%)",
                        f.subject(), f.predicate(), f.object(), f.confidence() * 100))
                .collect(Collectors.joining("\n"));

        try {
            String profileJson = callLLM(factsText, existingProfile);
            if (profileJson == null || profileJson.isBlank()) {
                return UserProfile.fromFacts(userId, facts);
            }
            return parseProfile(userId, profileJson);
        } catch (Exception e) {
            log.warn("[LLM-PROFILE] LLM profiling failed: {}", e.getMessage());
            return UserProfile.fromFacts(userId, facts);
        }
    }

    /**
     * 从最近的 Episodic Cells 增量更新用户画像。
     * 提取增量 Facts（通过内置 LLM 调用），合并到已有画像。
     */
    public UserProfile updateFromCells(String userId, List<MemoryCell> cells,
                                        String existingProfile) {
        if (cells == null || cells.isEmpty()) {
            return existingProfile != null
                    ? parseProfile(userId, existingProfile)
                    : UserProfile.empty(userId);
        }

        String conversation = cells.stream()
                .map(c -> {
                    String role = (String) c.attributes().getOrDefault("role", "user");
                    return "[" + role + "]: " + c.content();
                })
                .collect(Collectors.joining("\n"));

        String prompt = """
                你是用户画像分析器。从以下对话中提取用户画像信息，与已有画像合并。

                ## 已有画像
                %s

                ## 新对话内容
                %s

                ## 输出要求
                完成画像分析后必须调用 submitProfile 工具提交画像对象（工具参数即为结果 JSON），不要输出任何文字。
                """.formatted(existingProfile != null ? existingProfile : "暂无已有画像", conversation);

        try {
            GatewayRequest request = new GatewayRequest();
            request.setMessages(List.of(GatewayMessage.user(prompt)));
            GatewayOptions options = new GatewayOptions();
            options.setModel(model);
            options.setTemperature(0.2);
            options.setMaxTokens(2048);
            options.setJsonResponse(false);
            options.setForcedToolName(PROFILE_TOOL_NAME);
            options.setOutputSchema(PROFILE_SCHEMA);
            options.setForceToolChoice(true);
            options.setTimeout(Duration.ofSeconds(30));
            request.setOptions(options);
            request.setTraceId("llm-profile-" + UUID.randomUUID().toString().substring(0, 8));
            request.setCaller("LlmUserProfileConsolidator");

            String json = extractToolArguments(modelAdapter.call(request));
            if (json == null || json.isBlank()) {
                return UserProfile.empty(userId);
            }
            return parseProfile(userId, json);
        } catch (Exception e) {
            log.warn("[LLM-PROFILE] updateFromCells failed: {}", e.getMessage());
            return UserProfile.empty(userId);
        }
    }

    private String callLLM(String factsText, String existingProfile) {
        String prompt = """
                你是用户画像分析师。基于以下从用户对话中提取的事实，生成一份结构化的用户画像。

                ## 已有画像
                %s

                ## 提取的事实
                %s

                ## 输出要求
                完成画像分析后必须调用 submitProfile 工具提交画像对象（工具参数即为结果 JSON），不要输出任何文字。
                """.formatted(existingProfile != null ? existingProfile : "暂无已有画像", factsText);

        GatewayRequest request = new GatewayRequest();
        request.setMessages(List.of(GatewayMessage.user(prompt)));
        GatewayOptions options = new GatewayOptions();
        options.setModel(model);
        options.setTemperature(0.2);
        options.setMaxTokens(2048);
        options.setJsonResponse(false);
        options.setForcedToolName(PROFILE_TOOL_NAME);
        options.setOutputSchema(PROFILE_SCHEMA);
        options.setForceToolChoice(true);
        options.setTimeout(Duration.ofSeconds(30));
        request.setOptions(options);
        request.setTraceId("llm-profile-" + UUID.randomUUID().toString().substring(0, 8));
        request.setCaller("LlmUserProfileConsolidator");

        GatewayResponse response = modelAdapter.call(request);
        String args = extractToolArguments(response);
        log.info("[LLM-PROFILE] got profile response, length={}", args != null ? args.length() : 0);
        return args;
    }

    /** 提取强制工具参数；工具未调用时回退文本内容。 */
    private static String extractToolArguments(GatewayResponse response) {
        if (response == null) return null;
        String args = response.getToolCallArguments();
        if (args == null || args.isBlank()) {
            args = response.getContent();
        }
        return args;
    }

    UserProfile parseProfile(String userId, String rawJson) {
        try {
            String json = rawJson.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) {
                    json = json.substring(start, end).trim();
                }
            }
            Map<String, Object> data = JSON.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return UserProfile.fromMap(userId, data);
        } catch (Exception e) {
            log.warn("[LLM-PROFILE] parse failed: {}", e.getMessage());
            return UserProfile.empty(userId);
        }
    }

    /**
     * 用户画像数据结构。
     */
    public record UserProfile(
            String userId,
            Map<String, Object> identity,
            Map<String, Object> preferences,
            Map<String, Object> behavior,
            List<Map<String, Object>> keyFacts,
            List<String> changes,
            Instant updatedAt) {

        public static UserProfile empty(String userId) {
            return new UserProfile(userId, Map.of(), Map.of(), Map.of(),
                    List.of(), List.of(), Instant.now());
        }

        @SuppressWarnings("unchecked")
        public static UserProfile fromMap(String userId, Map<String, Object> data) {
            return new UserProfile(
                    userId,
                    (Map<String, Object>) data.getOrDefault("identity", Map.of()),
                    (Map<String, Object>) data.getOrDefault("preferences", Map.of()),
                    (Map<String, Object>) data.getOrDefault("behavior", Map.of()),
                    (List<Map<String, Object>>) data.getOrDefault("keyFacts", List.of()),
                    (List<String>) data.getOrDefault("changes", List.of()),
                    Instant.now());
        }

        public static UserProfile fromFacts(String userId, List<Fact> facts) {
            List<Map<String, Object>> factsList = facts.stream()
                    .map(f -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("fact", f.toTriple());
                        m.put("confidence", f.confidence());
                        return m;
                    })
                    .collect(Collectors.toList());
            return new UserProfile(userId, Map.of(), Map.of(), Map.of(),
                    factsList, List.of(), Instant.now());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", userId);
            map.put("identity", identity);
            map.put("preferences", preferences);
            map.put("behavior", behavior);
            map.put("keyFacts", keyFacts);
            map.put("changes", changes);
            map.put("updatedAt", updatedAt.toString());
            return map;
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# 用户画像 — ").append(userId).append("\n\n");
            sb.append("> 更新时间: ").append(updatedAt).append("\n\n");

            sb.append("## 身份\n");
            identity.forEach((k, v) -> sb.append("- **").append(k).append("**: ").append(v).append("\n"));

            sb.append("\n## 偏好\n");
            preferences.forEach((k, v) -> {
                if (v instanceof List<?> list) {
                    sb.append("- **").append(k).append("**: ").append(
                            list.stream().map(Object::toString).collect(Collectors.joining("、")))
                            .append("\n");
                } else {
                    sb.append("- **").append(k).append("**: ").append(v).append("\n");
                }
            });

            sb.append("\n## 行为模式\n");
            behavior.forEach((k, v) -> {
                if (v instanceof List<?> list) {
                    sb.append("- **").append(k).append("**: ").append(
                            list.stream().map(Object::toString).collect(Collectors.joining("、")))
                            .append("\n");
                } else {
                    sb.append("- **").append(k).append("**: ").append(v).append("\n");
                }
            });

            if (!keyFacts.isEmpty()) {
                sb.append("\n## 关键事实\n");
                for (Map<String, Object> fact : keyFacts) {
                    Object confidence = fact.get("confidence");
                    String pct = confidence instanceof Number n
                            ? String.format(" (%.0f%%)", n.doubleValue() * 100) : "";
                    sb.append("- ").append(fact.get("fact")).append(pct).append("\n");
                }
            }

            return sb.toString();
        }
    }
}
