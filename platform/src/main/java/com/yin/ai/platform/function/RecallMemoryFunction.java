package com.yin.ai.platform.function;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * recallMemory — LLM 按需召回历史记忆工具（agentic Memory 核心）。
 *
 * <p><b>背景</b>：agentic Memory 升级后，PlanExecute/DeepResearch 的每个步骤
 * 不再自动注入 memory 检索（节省 token + 减噪声）。但部分步骤仍需要参考
 * 用户历史偏好 / 相似案例 / 过往对话要点 —— 这时 LLM 在运行中主动调用本工具，
 * 拿到结构化记忆后写入上下文继续执行。
 *
 * <p><b>调用方式</b>：
 * <pre>
 * recallMemory(query: "用户对文章风格的偏好", topK: 5)
 * </pre>
 *
 * <p><b>输出</b>：最多 topK 条记忆（内容 + 分数），以纯文本返回给 LLM。
 * 使用方需要在工具列表中注册本 Function（callableByLLM=true）。
 *
 * <p>与 {@code rag_search}（RAG 知识库检索）分工：recallMemory 查的是
 * {@link MemoryRuntime}（用户长期记忆/会话历史），rag_search 查的是企业知识库。
 */
@Slf4j
public class RecallMemoryFunction extends AbstractFunction {

    public static final String NAME = "recallMemory";

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    /** 单条记忆最大截断长度 */
    private static final int CELL_MAX_CHARS = 400;
    /** 返回给 LLM 的最大字符预算（避免把上下文塞爆） */
    private static final int OUTPUT_MAX_CHARS = 8_000;

    private final MemoryRuntime memoryRuntime;

    public RecallMemoryFunction(MemoryRuntime memoryRuntime) {
        this.memoryRuntime = memoryRuntime;
    }

    private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
            .name(NAME)
            .description("召回用户的历史记忆（偏好、相似案例、过往对话要点）。"
                    + "当需要参考用户长期记忆（如写作风格偏好、历史决策、相似任务案例）时调用；"
                    + "普通检索知识库请用 rag_search，不需要历史记忆时不要调用本工具。")
            .category("memory")
            .risk(FunctionDefinition.Risk.READ_ONLY)
            .callableByLLM(true)
            .timeoutMs(5000)
            .parameters(List.of(
                    FunctionDefinition.ParamDef.builder()
                            .name("query")
                            .type(FunctionDefinition.ParamType.STRING)
                            .description("要检索的记忆查询（例如'用户对文章风格的偏好'、'用户上次关于XX的决策'）")
                            .required(true)
                            .build(),
                    FunctionDefinition.ParamDef.builder()
                            .name("topK")
                            .type(FunctionDefinition.ParamType.INTEGER)
                            .description("返回的记忆条数（默认 5，最大 10）")
                            .required(false)
                            .defaultValue(DEFAULT_TOP_K)
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
        Object queryObj = params != null ? params.get("query") : null;
        if (queryObj == null || String.valueOf(queryObj).isBlank()) {
            return FunctionResult.fail("INVALID_PARAMS", "query 参数不能为空");
        }
        String query = String.valueOf(queryObj).trim();

        int topK = DEFAULT_TOP_K;
        Object topKObj = params != null ? params.get("topK") : null;
        if (topKObj != null) {
            try {
                topK = Math.max(1, Math.min(MAX_TOP_K, Integer.parseInt(String.valueOf(topKObj))));
            } catch (NumberFormatException ignored) {
                // 非法 topK 用默认值
            }
        }

        try {
            MemoryQuery memoryQuery = MemoryQuery.byText(query, topK);
            QueryResult result = memoryRuntime.retrieve(memoryQuery,
                    new MemoryContext(null, null, null, "recallMemory", "function.recallMemory",
                            Instant.now(), Map.of()));

            List<ScoredMemoryCell> cells = result == null || result.results() == null
                    ? List.of() : result.results();
            if (cells.isEmpty()) {
                return FunctionResult.ok(Map.of(
                        "query", query,
                        "count", 0,
                        "memories", List.of(),
                        "note", "未找到匹配的历史记忆"));
            }

            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (int i = 0; i < cells.size(); i++) {
                ScoredMemoryCell scored = cells.get(i);
                String content = scored.cell() == null || scored.cell().content() == null
                        ? "" : scored.cell().content().trim();
                if (content.isEmpty()) continue;
                if (content.length() > CELL_MAX_CHARS) {
                    content = content.substring(0, CELL_MAX_CHARS) + "…";
                }
                String line = String.format("[%d] (score=%.2f) %s", i + 1, scored.score(), content);
                if (used + line.length() > OUTPUT_MAX_CHARS) {
                    break;
                }
                sb.append(line).append("\n");
                used += line.length();
            }

            String text = sb.toString().trim();
            if (text.isEmpty()) {
                return FunctionResult.ok(Map.of(
                        "query", query, "count", 0, "memories", List.of(),
                        "note", "匹配到的记忆内容为空"));
            }

            log.debug("[RECALL-MEM] query='{}' topK={} returned {} cells ({} chars)",
                    query, topK, cells.size(), text.length());
            return FunctionResult.ok(Map.of(
                    "query", query,
                    "count", cells.size(),
                    "memories", text,
                    "note", "以上为召回的用户历史记忆，供后续步骤参考"));
        } catch (Exception e) {
            log.warn("[RECALL-MEM] recall failed query='{}': {}", query, e.getMessage());
            return FunctionResult.fail("MEMORY_RECALL_ERROR", "记忆召回失败: " + e.getMessage());
        }
    }
}
