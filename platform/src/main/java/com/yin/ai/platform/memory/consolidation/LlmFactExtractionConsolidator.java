package com.yin.ai.platform.memory.consolidation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.semantic.Fact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM 驱动的 Fact 提取 Consolidator — 将对话 Cell 批量发送给 LLM，
 * 由 LLM 判断哪些信息值得长期保留并输出结构化 Fact。
 * <p>
 * 与 {@link FactExtractionConsolidator}（正则规则）的差异：
 * <ul>
 *   <li>不依赖固定正则模式，LLM 自行判断信息价值和关系类型</li>
 *   <li>输出置信度由 LLM 根据上下文评估，而非固定 0.6</li>
 *   <li>支持复杂关系提取（因果、偏好、计划、身份等）</li>
 *   <li>自动去重与冲突检测</li>
 * </ul>
 * <p>
 * LLM 不可用时降级到 {@link FactExtractionConsolidator} 规则引擎。
 */
public class LlmFactExtractionConsolidator implements MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(LlmFactExtractionConsolidator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fact 提取工具契约：{facts:[{subject,predicate,object,confidence}]} */
    private static final Map<String, Object> FACTS_SCHEMA = readSchema("""
            {"type":"object","properties":{"facts":{"type":"array","description":"提取的事实三元组列表",
              "items":{"type":"object","properties":{
                "subject":{"type":"string","description":"主体"},
                "predicate":{"type":"string","description":"谓词"},
                "object":{"type":"string","description":"客体"},
                "confidence":{"type":"number","description":"置信度(0.0-1.0)"}},
              "required":["subject","predicate","object","confidence"]}}},
             "required":["facts"]}
            """);

    private static final String FACTS_TOOL_NAME = "submitFacts";

    private static final String FACTS_TOOL_DESCRIPTION =
            "提交提取的事实三元组。完成提取后必须调用本工具，把事实数组作为工具参数返回，不要输出任何文字。";

    private static Map<String, Object> readSchema(String jsonSchema) {
        try {
            return JSON.readValue(jsonSchema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON schema constant", e);
        }
    }

    private final ModelAdapter modelAdapter;
    private final FactExtractionConsolidator fallback;
    private final String model;
    private final int maxCellsPerBatch;

    public LlmFactExtractionConsolidator(ModelAdapter modelAdapter) {
        this(modelAdapter, "deepseek-chat", 30);
    }

    public LlmFactExtractionConsolidator(ModelAdapter modelAdapter, String model, int maxCellsPerBatch) {
        this.modelAdapter = modelAdapter;
        this.model = model;
        this.maxCellsPerBatch = maxCellsPerBatch;
        this.fallback = new FactExtractionConsolidator();
    }

    @Override
    public ConsolidationResult consolidate(MemoryId memoryId, List<MemoryCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return ConsolidationResult.empty();
        }

        List<Fact> allFacts = new ArrayList<>();
        List<String> processedCellIds = new ArrayList<>();

        // 分批处理
        for (int i = 0; i < cells.size(); i += maxCellsPerBatch) {
            int end = Math.min(i + maxCellsPerBatch, cells.size());
            List<MemoryCell> batch = cells.subList(i, end);
            try {
                List<Fact> batchFacts = extractWithLLM(batch);
                allFacts.addAll(batchFacts);
                log.info("[LLM-FACT] extracted {} facts from batch {}/{} ({} cells)",
                        batchFacts.size(), (i / maxCellsPerBatch) + 1,
                        (cells.size() + maxCellsPerBatch - 1) / maxCellsPerBatch, batch.size());
            } catch (Exception e) {
                log.warn("[LLM-FACT] LLM extraction failed, falling back to rules: {}", e.getMessage());
                ConsolidationResult fallbackResult = fallback.consolidate(memoryId, batch);
                allFacts.addAll(fallbackResult.facts());
            }
            for (MemoryCell cell : batch) {
                processedCellIds.add(cell.cellId().id());
            }
        }

        return new ConsolidationResult(List.of(), allFacts, List.of(), processedCellIds,
                Map.of("extractor", "llm", "model", model, "totalBatches",
                        (cells.size() + maxCellsPerBatch - 1) / maxCellsPerBatch));
    }

    private List<Fact> extractWithLLM(List<MemoryCell> cells) {
        String conversation = cells.stream()
                .map(c -> {
                    String role = (String) c.attributes().getOrDefault("role", "user");
                    return "[" + role + "]: " + c.content();
                })
                .collect(Collectors.joining("\n"));

        String prompt = buildExtractionPrompt(conversation);

        GatewayRequest request = new GatewayRequest();
        request.setMessages(List.of(GatewayMessage.user(prompt)));
        GatewayOptions options = new GatewayOptions();
        options.setModel(model);
        options.setTemperature(0.1);
        options.setMaxTokens(2048);
        options.setJsonResponse(false);
        options.setForcedToolName(FACTS_TOOL_NAME);
        options.setOutputSchema(FACTS_SCHEMA);
        options.setForceToolChoice(true);
        options.setTimeout(Duration.ofSeconds(10));
        request.setOptions(options);
        request.setTraceId("llm-fact-" + UUID.randomUUID().toString().substring(0, 8));
        request.setCaller("LlmFactExtractionConsolidator");

        GatewayResponse response = modelAdapter.call(request);
        String args = response != null ? response.getToolCallArguments() : null;
        if (args == null || args.isBlank()) {
            log.warn("[LLM-FACT] empty tool-call response from LLM");
            return List.of();
        }

        return parseFactResponse(args, cells);
    }

    String buildExtractionPrompt(String conversation) {
        return """
                你是一个知识提取器。从以下对话中提取值得长期保留的事实信息。

                ## 提取规则
                1. 只提取对后续对话有价值的信息：用户身份、偏好、计划、知识、关系
                2. 每条事实用三元组 (subject, predicate, object) 表达
                3. confidence 取值 0.0-1.0：明确陈述=1.0，推测=0.5-0.7，模糊暗示=0.3-0.5
                4. 不要提取问候语、闲聊寒暄、临时状态
                5. 已存在的相似事实不用重复提取
                6. 用户明确纠正或推翻之前说法时，confidence 应更高（1.0）以覆盖旧事实

                ## 输出要求
                完成提取后必须调用 submitFacts 工具提交事实三元组数组（工具参数即为结果 JSON），不要输出任何文字。

                ## 对话内容
                %s
                """.formatted(conversation);
    }

    List<Fact> parseFactResponse(String llmResponse, List<MemoryCell> cells) {
        try {
            String json = llmResponse.trim();
            // 去掉可能的 markdown 代码块标记
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) {
                    json = json.substring(start, end).trim();
                }
            }

            // 工具参数为 {facts:[...]}；兼容裸数组
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(json);
            com.fasterxml.jackson.databind.JsonNode arr = root.isArray() ? root : root.get("facts");
            if (arr == null || !arr.isArray()) {
                log.warn("[LLM-FACT] no facts array in response");
                return List.of();
            }

            String firstCellId = cells.get(0).cellId().id();
            List<Fact> facts = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode raw : arr) {
                String subject = raw.path("subject").asText("");
                String predicate = raw.path("predicate").asText("");
                String object = raw.path("object").asText("");
                double confidence = raw.has("confidence") ? raw.get("confidence").asDouble() : 0.7;

                if (subject.isBlank() || predicate.isBlank() || object.isBlank()) {
                    continue;
                }

                facts.add(Fact.of("llm-fact-" + UUID.randomUUID(), subject, predicate, object, confidence)
                        .withSourceCell(firstCellId));
            }

            return facts;
        } catch (Exception e) {
            log.warn("[LLM-FACT] failed to parse LLM response: {}", e.getMessage());
            return List.of();
        }
    }
}
