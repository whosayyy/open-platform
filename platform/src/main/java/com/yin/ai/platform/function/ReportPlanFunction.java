package com.yin.ai.platform.function;

import com.yin.ai.platform.agent.pattern.StepTextCleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * reportPlan — LLM 语义进度上报工具。
 *
 * <p>写作/研究类 Agent 在执行多阶段任务时调用此工具，
 * 向用户展示结构化的阶段进度（如"大纲规划 → 素材检索 → 正文撰写 → 审校保存"）。
 *
 * <p><b>P1-新约束</b>：后端权威接管文本展示字段。LLM 上报的 name/detail/summary 在此做净化与长度限制：
 * <ul>
 *   <li>name：≤30 字，自动剥离嵌 JSON（如 {@code {"goal":"用户..."}}）</li>
 *   <li>status：必须为 pending/active/done/failed</li>
 *   <li>detail：≤120 字，可选</li>
 *   <li>summary：≤120 字，可选，步骤完成时一句话总结，对应前端 wsPlanStages.detail 字段</li>
 * </ul>
 *
 * <p>前端不再做 humanize。所有展示字段已净化，安全呈现。
 *
 * <p>本工具不执行业务逻辑，仅校验输入并原样返回。
 * 真正的 PLAN_UPDATE 事件由 ReActLoop 在工具执行后发射。
 */
public class ReportPlanFunction extends AbstractFunction {

    /** name 最大长度（含中文） */
    private static final int NAME_MAX = 30;
    /** detail 最大长度 */
    private static final int DETAIL_MAX = 120;
    /** summary 最大长度 */
    private static final int SUMMARY_MAX = 120;
    /** 合法 status 集合 */
    private static final Set<String> VALID_STATUSES = Set.of("pending", "active", "done", "failed");

    private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
            .name("reportPlan")
            .description("上报当前任务的阶段进度。在开始创作时调用一次定义所有阶段，每完成一个阶段后再次调用更新状态。"
                    + "stages 数组中每个元素：name(阶段名, ≤30字简短中文摘要), status(pending|active|done|failed), "
                    + "detail(可选说明, ≤120字), summary(步骤完成时一句话总结, ≤120字)。"
                    + "name 必须是人类可读的中文短语，不要放 JSON 对象或结构化字段。")
            .category("system")
            .risk(FunctionDefinition.Risk.WRITE)
            .callableByLLM(true)
            .timeoutMs(3000)
            .parameters(List.of(
                    FunctionDefinition.ParamDef.builder()
                            .name("stages")
                            .type(FunctionDefinition.ParamType.ARRAY)
                            .description("阶段数组，每项含 name(string ≤30字), status(pending|active|done|failed), detail/string≤120, summary/string≤120")
                            .required(true)
                            .build()
            ))
            .build();

    @Override
    public FunctionDefinition getDefinition() {
        return DEFINITION;
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        Map<String, Object> params = input.getParameters();
        Object stages = params != null ? params.get("stages") : null;
        if (!(stages instanceof List)) {
            return FunctionResult.fail("INVALID_PARAMS", "stages 参数必须是数组");
        }
        List<?> rawList = (List<?>) stages;

        // 后端授权净化：每条 stage 做字段校验、JSON 净化、长度截断
        List<Map<String, Object>> cleaned = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) item;
            String name = cleanField(raw.get("name"), NAME_MAX);
            if (name == null || name.isEmpty()) {
                // name 是必需的 + 被净化后空了 → 拒绝整条
                continue;
            }
            String status = normalizeStatus(String.valueOf(raw.getOrDefault("status", "")));
            String detail = cleanField(raw.get("detail"), DETAIL_MAX);
            String summary = cleanField(raw.get("summary"), SUMMARY_MAX);

            java.util.LinkedHashMap<String, Object> stage = new java.util.LinkedHashMap<>();
            stage.put("name", name);
            stage.put("status", status);
            if (detail != null && !detail.isEmpty()) stage.put("detail", detail);
            if (summary != null && !summary.isEmpty()) stage.put("summary", summary);
            cleaned.add(stage);
        }

        if (cleaned.isEmpty()) {
            return FunctionResult.fail("INVALID_PARAMS", "stages 经净化后无有效阶段");
        }

        // 原样返回，ReActLoop 负责发射 PLAN_UPDATE 事件
        return FunctionResult.ok(Map.of("stages", cleaned, "received", true, "sanitized", true));
    }

    /**
     * 净化字段：剥离嵌 JSON（如 {@code {"goal":"用户..."}}）+ 截断长度。
     * @return 净化后文本；空白/null → null
     */
    private static String cleanField(Object raw, int maxLen) {
        if (raw == null) return null;
        String cleaned = StepTextCleaner.clean(String.valueOf(raw), maxLen);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    private static String normalizeStatus(String raw) {
        if (raw == null) return "pending";
        String s = raw.trim().toLowerCase();
        if (VALID_STATUSES.contains(s)) return s;
        return "pending";
    }
}
