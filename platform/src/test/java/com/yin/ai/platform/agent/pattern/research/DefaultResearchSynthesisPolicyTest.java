package com.yin.ai.platform.agent.pattern.research;

import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.AgentMode;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.task.ContextStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@link DefaultResearchSynthesisPolicy}：
 * 来源收集只信任已完成 web_search 步骤的输出；综合报告引用未验证 URL 时替换为占位。
 */
@DisplayName("DefaultResearchSynthesisPolicy")
class DefaultResearchSynthesisPolicyTest {

    private final DefaultResearchSynthesisPolicy policy = new DefaultResearchSynthesisPolicy();

    private AgentExecutionContext ctx() {
        return new AgentExecutionContext("t1", "s1", "u1", AgentMode.DEEP_RESEARCH,
                null, mock(EventPublisher.class), mock(ContextStore.class));
    }

    private AgentExecutionContext ctxWithSearchStep(String output) {
        AgentExecutionContext ctx = ctx();
        TaskStep search = TaskStep.pending("search", "搜索", "web_search", List.of()).withOutput(output);
        TaskStep synth = TaskStep.pending("synthesize", "综合", "llm", List.of("search"))
                .withOutput("草稿");
        ctx.setPlan(new TaskPlan("调研", List.of(search, synth)));
        return ctx;
    }

    @Test
    @DisplayName("从已完成 web_search 步骤输出解析来源")
    void collectSourcesParsesWebSearchOutput() {
        String output = """
                {"query":"AI 芯片市场","days":7,"results":[
                  {"title":"2026 AI 芯片市场报告","url":"https://example.com/a","source":"行业报告",
                   "published_date":"2026-08-01","content":"..."},
                  {"title":"另一篇","url":"https://example.com/b.","source":"媒体"}
                ]}
                """;
        AgentExecutionContext ctx = ctxWithSearchStep(output);

        List<ResearchSynthesisPolicy.SourceRef> sources = policy.collectSources(ctx);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).url()).isEqualTo("https://example.com/a");
        assertThat(sources.get(0).title()).isEqualTo("2026 AI 芯片市场报告");
        assertThat(sources.get(0).source()).isEqualTo("行业报告");
        assertThat(sources.get(1).url()).isEqualTo("https://example.com/b");
    }

    @Test
    @DisplayName("跳过未完成步骤与非 web_search 步骤")
    void collectSourcesSkipsIncompleteAndNonSearchSteps() {
        AgentExecutionContext ctx = ctx();
        TaskStep pendingSearch = TaskStep.pending("search", "搜索", "web_search", List.of());
        TaskStep llmDone = TaskStep.pending("write", "写作", "llm", List.of())
                .withOutput("{\"results\":[{\"url\":\"https://example.com/fake\"}]}");
        ctx.setPlan(new TaskPlan("调研", List.of(pendingSearch, llmDone)));

        assertThat(policy.collectSources(ctx)).isEmpty();
    }

    @Test
    @DisplayName("已验证来源保留，未验证来源替换为 [未验证来源]")
    void verifyKeepsVerifiedUrlAndSanitizesForeign() {
        List<ResearchSynthesisPolicy.SourceRef> sources = List.of(
                new ResearchSynthesisPolicy.SourceRef("标题", "https://example.com/verified", "来源"));
        String report = "根据 https://example.com/verified 的数据，市场增长 12%。"
                + "另见 https://evil.example/x 的说法。";

        ResearchSynthesisPolicy.Verdict verdict = policy.verify(report, sources);

        assertThat(verdict.clean()).isFalse();
        assertThat(verdict.foreignUrls()).containsExactly("https://evil.example/x");
        assertThat(verdict.sanitizedReport())
                .contains("https://example.com/verified")
                .doesNotContain("https://evil.example/x")
                .contains("[未验证来源]");
    }

    @Test
    @DisplayName("全部来源已验证 → clean，报告不变")
    void verifyCleanWhenAllVerified() {
        List<ResearchSynthesisPolicy.SourceRef> sources = List.of(
                new ResearchSynthesisPolicy.SourceRef("A", "https://example.com/a", "src"));
        String report = "数据来自 https://example.com/a";

        ResearchSynthesisPolicy.Verdict verdict = policy.verify(report, sources);

        assertThat(verdict.clean()).isTrue();
        assertThat(verdict.sanitizedReport()).isEqualTo(report);
        assertThat(verdict.foreignUrls()).isEmpty();
    }

    @Test
    @DisplayName("空报告 → clean")
    void verifyBlankReportClean() {
        assertThat(policy.verify("", List.of()).clean()).isTrue();
    }

    @Test
    @DisplayName("formatSources 输出编号列表")
    void formatSourcesProducesNumberedList() {
        List<ResearchSynthesisPolicy.SourceRef> sources = List.of(
                new ResearchSynthesisPolicy.SourceRef("A", "https://example.com/a", "src1"),
                new ResearchSynthesisPolicy.SourceRef("B", "https://example.com/b", ""));

        String formatted = policy.formatSources(sources);

        assertThat(formatted).contains("1. A（src1）").contains("2. B — https://example.com/b");
    }

    @Test
    @DisplayName("无来源时 formatSources 提示不得编造")
    void formatSourcesEmptyReturnsNoVerifiedPlaceholder() {
        assertThat(policy.formatSources(List.of())).contains("无已验证来源");
    }
}
