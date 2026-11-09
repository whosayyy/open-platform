package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.memory.consolidation.LlmUserProfileConsolidator.UserProfile;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
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
 * 验证 {@link LlmUserProfileConsolidator} 的工具调用式画像提炼（submitProfile）：
 * 强制工具契约、解析与降级回退。
 */
@DisplayName("LlmUserProfileConsolidator")
class LlmUserProfileConsolidatorTest {

    private static final String PROFILE_JSON = """
            {"identity":{"role":"记者","expertise":["科技"]},
             "preferences":{"likes":["篮球"],"dislikes":[]},
             "behavior":{"activeHours":"晚上","interactionStyle":"简洁","topicsOfInterest":["AI"]},
             "keyFacts":[{"fact":"用户喜欢篮球","confidence":0.9}],
             "changes":["新增偏好"]}
            """;

    private final ModelAdapter modelAdapter = mock(ModelAdapter.class);

    private LlmUserProfileConsolidator consolidator() {
        return new LlmUserProfileConsolidator(modelAdapter);
    }

    private List<Fact> facts() {
        return List.of(
                Fact.of("1", "用户", "喜欢", "篮球", 0.9),
                Fact.of("2", "用户", "从事", "写作", 0.7));
    }

    @Test
    @DisplayName("工具参数有效 → 解析为完整画像，强制 submitProfile 工具")
    void consolidateParsesProfileFromToolArgs() {
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments(PROFILE_JSON);
        when(modelAdapter.call(any())).thenReturn(response);
        LlmUserProfileConsolidator consolidator = consolidator();

        UserProfile profile = consolidator.consolidate("u1", facts(), null);

        assertThat(profile.userId()).isEqualTo("u1");
        assertThat(profile.identity()).containsEntry("role", "记者");
        assertThat(profile.keyFacts()).hasSize(1);
        assertThat(profile.changes()).containsExactly("新增偏好");

        ArgumentCaptor<GatewayRequest> captor = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelAdapter).call(captor.capture());
        GatewayOptions options = captor.getValue().getOptions();
        assertThat(options.getForcedToolName()).isEqualTo("submitProfile");
        assertThat(options.isForceToolChoice()).isTrue();
        assertThat(options.getOutputSchema()).containsKey("type");
        assertThat(options.isJsonResponse()).isFalse();
    }

    @Test
    @DisplayName("无 facts → 直接返回空画像，不调用 LLM")
    void emptyFactsReturnsEmptyProfile() {
        LlmUserProfileConsolidator consolidator = consolidator();

        UserProfile profile = consolidator.consolidate("u1", List.of(), null);

        assertThat(profile.userId()).isEqualTo("u1");
        assertThat(profile.keyFacts()).isEmpty();
        verify(modelAdapter, never()).call(any());
    }

    @Test
    @DisplayName("工具参数为空但 content 有 JSON → 回退 content 解析")
    void contentFallbackWhenNoToolArgs() {
        GatewayResponse response = new GatewayResponse(PROFILE_JSON);
        when(modelAdapter.call(any())).thenReturn(response);
        LlmUserProfileConsolidator consolidator = consolidator();

        UserProfile profile = consolidator.consolidate("u1", facts(), null);

        assertThat(profile.identity()).containsEntry("role", "记者");
    }

    @Test
    @DisplayName("响应无法解析 → 回退空画像")
    void invalidResponseReturnsEmptyProfile() {
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments("not-json");
        when(modelAdapter.call(any())).thenReturn(response);
        LlmUserProfileConsolidator consolidator = consolidator();

        UserProfile profile = consolidator.consolidate("u1", facts(), null);

        assertThat(profile.userId()).isEqualTo("u1");
        assertThat(profile.keyFacts()).isEmpty();
    }

    @Test
    @DisplayName("updateFromCells 从对话 cells 更新画像")
    void updateFromCellsBuildsProfile() {
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments(PROFILE_JSON);
        when(modelAdapter.call(any())).thenReturn(response);
        LlmUserProfileConsolidator consolidator = consolidator();
        List<MemoryCell> cells = List.of(MemoryCell.of(
                MemoryCellId.of("m1"), MemoryType.EPISODIC, "我喜欢篮球"));

        UserProfile profile = consolidator.updateFromCells("u1", cells, null);

        assertThat(profile.identity()).containsEntry("role", "记者");
        assertThat(profile.keyFacts()).hasSize(1);
    }

    @Test
    @DisplayName("updateFromCells 空 cells → 复用已有画像或返回空")
    void updateFromCellsEmptyCells() {
        LlmUserProfileConsolidator consolidator = consolidator();

        UserProfile empty = consolidator.updateFromCells("u1", List.of(), null);
        assertThat(empty.keyFacts()).isEmpty();

        UserProfile reused = consolidator.updateFromCells("u1", List.of(), PROFILE_JSON);
        assertThat(reused.identity()).containsEntry("role", "记者");
        verify(modelAdapter, never()).call(any());
    }

    @Test
    @DisplayName("parseProfile 直接解析合法 JSON")
    void parseProfileDirect() {
        LlmUserProfileConsolidator consolidator = consolidator();

        UserProfile profile = consolidator.parseProfile("u1", PROFILE_JSON);

        assertThat(profile.identity()).containsEntry("role", "记者");
        assertThat(profile.behavior()).containsEntry("activeHours", "晚上");
    }
}
