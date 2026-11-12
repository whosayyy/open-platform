package com.yin.ai.platform.agent.pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import com.yin.ai.platform.function.StepTextCleaner;

/**
 * LLM 计划解析器 — 把 Planner LLM 返回的 JSON 步骤数组解析为可执行的 {@link TaskPlan}。
 * <p>
 * 设计理由：LLM 的 JSON 输出不可控（可能带 ```json 代码块、前后缀散文、对象包裹数组），
 * 且可能包含非法字段（重复 id、未知 executor、悬空依赖）。本类负责"提取 + 解析 + 净化"：
 * <ul>
 *   <li>提取：括号深度扫描找最外层 JSON，正确处理对象数组，避免截断</li>
 *   <li>解析：兼容裸数组与 {@code {"steps":[...]}} 等对象包裹</li>
 *   <li>净化：跳过空步骤、id 去重、非法 executor 降级为 llm、裁剪悬空依赖、步数上限</li>
 * </ul>
 * 解析/净化后无有效步骤时返回 {@code null}，调用方回退到默认计划。
 */
public final class PlanJsonParser {

    private static final Logger log = LoggerFactory.getLogger(PlanJsonParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STEPS = 8;
    private static final int MAX_NEW_STEPS = 4;
    private static final String[] WRAPPER_KEYS = {"steps", "plan", "items", "tasks"};

    private PlanJsonParser() {}

    /**
     * 从 LLM 原始输出解析执行计划。
     *
     * @param response           LLM 原始输出（JSON 数组或带包裹/代码块/散文）
     * @param goal               任务目标（写入计划）
     * @param availableExecutor  executor 可用性判定（如 dispatcher.isAvailable）
     * @return 净化后的计划；无法解析或无有效步骤时返回 null
     */
    public static TaskPlan parse(String response, String goal, Predicate<String> availableExecutor) {
        String json = extractJson(response);
        if (json == null) {
            log.warn("[PLAN-PARSE] no JSON found in response, returning null");
            return null;
        }

        List<JsonStep> defs = readSteps(json);
        if (defs == null || defs.isEmpty()) {
            log.warn("[PLAN-PARSE] no steps array in JSON, returning null");
            return null;
        }

        List<TaskStep> steps = buildSteps(defs, availableExecutor, MAX_STEPS, true);
        if (steps.isEmpty()) {
            log.warn("[PLAN-PARSE] all steps filtered out, returning null");
            return null;
        }

        log.info("[PLAN-PARSE] parsed {} steps from LLM plan", steps.size());
        return new TaskPlan(goal, steps);
    }

    /**
     * 从 Reflector 返回的 REPLAN 工具参数中解析"追加步骤"。
     * <p>
     * 与 {@link #parse} 的差异：
     * - 从 {@code {decision, reason, hint, newSteps:[...]}} 对象中取 {@code newSteps} 数组
     * - 依赖不做本地裁剪：允许引用既有计划中的步骤 id（追加步骤常依赖已完成步骤的输出）
     * - 上限较小（防止一次追加过多导致计划膨胀）
     *
     * @return 净化后的追加步骤；无法解析或无有效步骤时返回空列表
     */
    public static List<TaskStep> parseNewSteps(String response, Predicate<String> availableExecutor) {
        String json = extractJson(response);
        if (json == null) {
            return List.of();
        }

        List<JsonStep> defs = null;
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isObject()) {
                JsonNode arr = node.get("newSteps");
                if (arr != null && arr.isArray()) {
                    defs = MAPPER.readValue(arr.traverse(), new TypeReference<List<JsonStep>>() {});
                }
            }
        } catch (Exception ignored) {
            // fallthrough to bare-array attempt
        }
        if (defs == null) {
            // 兼容直接返回裸步骤数组
            defs = readSteps(json);
        }

        return buildSteps(defs, availableExecutor, MAX_NEW_STEPS, false);
    }

    /**
     * 步骤净化公共逻辑：跳过空步骤、id 去重、非法 executor 降级为 llm、
     * 依赖裁剪（trimDeps=true 时仅保留本批次内的 id）。
     */
    private static List<TaskStep> buildSteps(List<JsonStep> defs, Predicate<String> availableExecutor,
                                             int maxSteps, boolean trimDeps) {
        if (defs == null || defs.isEmpty()) {
            return List.of();
        }

        List<TaskStep> steps = new ArrayList<>();
        List<List<String>> rawDeps = new ArrayList<>();
        Map<String, Integer> idCount = new HashMap<>();

        for (JsonStep def : defs) {
            if (steps.size() >= maxSteps) {
                break;
            }
            String id = def.id() == null ? "" : def.id().trim();
            // 后端授权净化：LLM 经常把 description 写成 {"goal":"用户...","articleType":"分析"} 内嵌 JSON。
            // StepTextCleaner 提取可读字段后 trim 并截断 30 字，避免污染 UI。
            String desc = StepTextCleaner.clean(def.description(), 30);
            if (id.isEmpty() || desc.isEmpty()) {
                continue;
            }
            // [B-agentic Memory] 解析 requiresMemory（LLM 显式 opt-in 标记）
            boolean requiresMemory = Boolean.TRUE.equals(def.requiresMemory());
            // [UI 优化] 解析 reason（"为什么这么做"），StepTextCleaner 截断 50 字
            String reason = def.reason() != null ? StepTextCleaner.clean(def.reason(), 50) : null;
            steps.add(TaskStep.pending(uniqueId(id, idCount), desc,
                    sanitizeExecutor(def.executor(), availableExecutor), List.of(), reason)
                    .withRequiresMemory(requiresMemory));
            rawDeps.add(def.dependsOn() != null ? def.dependsOn() : List.of());
        }

        if (steps.isEmpty()) {
            return List.of();
        }

        Set<String> ids = steps.stream().map(TaskStep::id).collect(Collectors.toSet());
        for (int i = 0; i < steps.size(); i++) {
            TaskStep step = steps.get(i);
            List<String> deps = rawDeps.get(i).stream()
                    .filter(dep -> !dep.equals(step.id()))
                    .distinct()
                    .filter(dep -> !trimDeps || ids.contains(dep))
                    .toList();
            steps.set(i, TaskStep.pending(step.id(), step.description(), step.executor(), deps, step.reason()));
        }
        return steps;
    }

    // === JSON 提取 ===

    /**
     * 从 LLM 输出中提取最外层 JSON 片段。
     * 兼容：```json 代码块、前后缀散文、裸对象/数组。
     */
    static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();

        // 优先取 ``` ... ``` 代码块内的内容
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int nl = trimmed.indexOf('\n', fenceStart);
            int contentStart = nl >= 0 ? nl + 1 : fenceStart + 3;
            int fenceEnd = trimmed.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                trimmed = trimmed.substring(contentStart, fenceEnd).trim();
            }
        }

        // 括号深度扫描找最外层 [ 或 { 的匹配闭合
        char open = 0;
        int start = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '[' || c == '{') {
                open = c;
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }

        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return trimmed.substring(start, i + 1);
            }
        }
        return null;
    }

    // === 解析 ===

    private static List<JsonStep> readSteps(String json) {
        // 直接数组
        try {
            List<JsonStep> list = MAPPER.readValue(json, new TypeReference<List<JsonStep>>() {});
            if (list != null) {
                return list;
            }
        } catch (Exception ignored) {
            // fallthrough
        }

        // 对象包裹：{"steps": [...]} / {"plan": [...]} / ...
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isObject()) {
                for (String key : WRAPPER_KEYS) {
                    JsonNode arr = node.get(key);
                    if (arr != null && arr.isArray()) {
                        return MAPPER.readValue(arr.traverse(), new TypeReference<List<JsonStep>>() {});
                    }
                }
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        return null;
    }

    // === 净化 ===

    private static String uniqueId(String id, Map<String, Integer> idCount) {
        int count = idCount.merge(id, 1, Integer::sum);
        return count == 1 ? id : id + "-" + count;
    }

    private static String sanitizeExecutor(String executor, Predicate<String> availableExecutor) {
        if (executor == null || executor.isBlank()) {
            return "llm";
        }
        String e = executor.trim();
        if (availableExecutor != null && availableExecutor.test(e)) {
            return e;
        }
        log.debug("[PLAN-PARSE] executor '{}' unavailable, degrading to llm", e);
        return "llm";
    }

    /** LLM 返回的步骤 JSON 定义（与 TaskStep 解耦，容忍缺字段） */
    private record JsonStep(String id, String description, String executor,
                            List<String> dependsOn, Boolean requiresMemory, String reason) {}
}
