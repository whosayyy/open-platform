package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.semantic.Fact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link LlmFactExtractionConsolidator} 的工具调用式 Fact 提取（submitFacts）：
 * 强制工具契约、解析、以及 LLM 异常时降级规则引擎。
 */
@DisplayName("LlmFactExtractionConsolidator")
class LlmFactExtractionConsolidatorTest {

    private static final MemoryId MEMORY_ID = MemoryId.of("m1", MemoryType.EPISODIC, "u1");

    private static final String FACTS_JSON = """
            {"facts":[{"subject":"用户","predicate":"喜欢","object":"篮球","confidence":0.9}]}
            """;

    private final ModelAdapter modelAdapter = mock(ModelAdapter.class);

    private LlmFactExtractionConsolidator consolidator() {
        return new LlmFactExtractionConsolidator(modelAdapter);
    }

    private List<MemoryCell> cells(String content) {
        return List.of(MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, content)
                .withAttribute("role", "user"));
    }

    @Test
    @DisplayName("工具参数有效 → 提取 Fact，标记已处理 cell，强制 submitFacts 工具")
    void consolidateParsesFactsFromToolArgs() {
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments(FACTS_JSON);
        when(modelAdapter.call(any())).thenReturn(response);
        LlmFactExtractionConsolidator consolidator = consolidator();

        ConsolidationResult result = consolidator.consolidate(MEMORY_ID, cells("我提到了篮球"));

        assertThat(result.facts()).hasSize(1);
        Fact fact = result.facts().get(0);
        assertThat(fact.subject()).isEqualTo("用户");
        assertThat(fact.predicate()).isEqualTo("喜欢");
        assertThat(fact.object()).isEqualTo("篮球");
        assertThat(result.consolidatedCellIds()).containsExactly("c1");
        assertThat(result.metadata()).containsEntry("extractor", "llm");

        ArgumentCaptor<GatewayRequest> captor = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelAdapter).call(captor.capture());
        GatewayOptions options = captor.getValue().getOptions();
        assertThat(options.getForcedToolName()).isEqualTo("submitFacts");
        assertThat(options.isForceToolChoice()).isTrue();
        assertThat(options.getOutputSchema()).containsKey("type");
    }

    @Test
    @DisplayName("空 cells → 空结果，不调用 LLM")
    void emptyCellsReturnsEmpty() {
        LlmFactExtractionConsolidator consolidator = consolidator();

        ConsolidationResult result = consolidator.consolidate(MEMORY_ID, List.of());

        assertThat(result.isEmpty()).isTrue();
        verify(modelAdapter, never()).call(any());
    }

    @Test
    @DisplayName("工具参数为空 → 无 Fact，但 cell 仍标记已处理")
    void blankToolArgsYieldsNoFacts() {
        when(modelAdapter.call(any())).thenReturn(new GatewayResponse());
        LlmFactExtractionConsolidator consolidator = consolidator();

        ConsolidationResult result = consolidator.consolidate(MEMORY_ID, cells("内容"));

        assertThat(result.facts()).isEmpty();
        assertThat(result.consolidatedCellIds()).containsExactly("c1");
        assertThat(result.metadata()).containsEntry("extractor", "llm");
    }

    @Test
    @DisplayName("LLM 调用异常 → 降级规则引擎提取")
    void exceptionFallsBackToRules() {
        when(modelAdapter.call(any())).thenThrow(new RuntimeException("provider down"));
        LlmFactExtractionConsolidator consolidator = consolidator();

        ConsolidationResult result = consolidator.consolidate(MEMORY_ID, cells("小明喜欢篮球"));

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).anySatisfy(f -> {
            assertThat(f.subject()).isEqualTo("小明");
            assertThat(f.predicate()).isEqualTo("喜欢");
            assertThat(f.object()).isEqualTo("篮球");
        });
    }

    @Test
    @DisplayName("parseFactResponse 直接解析工具参数")
    void parseFactResponseDirect() {
        LlmFactExtractionConsolidator consolidator = consolidator();
        List<MemoryCell> source = cells("内容");

        List<Fact> facts = consolidator.parseFactResponse(FACTS_JSON, source);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).sourceCellIds()).containsExactly("c1");
    }

    @Test
    @DisplayName("parseFactResponse 非法 JSON → 空列表")
    void parseFactResponseInvalidJson() {
        LlmFactExtractionConsolidator consolidator = consolidator();

        List<Fact> facts = consolidator.parseFactResponse("not-json", cells("内容"));

        assertThat(facts).isEmpty();
    }

    @Test
    @DisplayName("buildExtractionPrompt 包含工具指令")
    void buildExtractionPromptIncludesToolInstruction() {
        LlmFactExtractionConsolidator consolidator = consolidator();

        String prompt = consolidator.buildExtractionPrompt("[user]: 我喜欢篮球");

        assertThat(prompt).contains("submitFacts");
        assertThat(prompt).contains("[user]: 我喜欢篮球");
    }
}
