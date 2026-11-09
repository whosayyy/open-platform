package com.yin.ai.platform.core.agent.research;

import com.yin.ai.platform.core.agent.plan.AgentPlan;
import com.yin.ai.platform.core.agent.plan.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultResearchSynthesisPolicyTest {

    private final DefaultResearchSynthesisPolicy policy =
            DefaultResearchSynthesisPolicy.standard();

    @Test
    void collectsStructuredAndFreeFormSourcesWithoutDuplicates() {
        AgentPlan plan = new AgentPlan("goal", List.of(
                completed("structured", """
                        {"results":[{"title":"Official report",
                          "url":"https://example.com/report/",
                          "source":"Example","snippet":"verified"}]}
                        """),
                completed("free-form", "Supporting evidence: https://example.com/report "
                        + "and https://second.example.org/source.")));

        List<ResearchSource> sources = policy.collectSources(plan);

        assertThat(sources).hasSize(2);
        assertThat(sources.getFirst().title()).isEqualTo("Official report");
        assertThat(sources).extracting(ResearchSource::url)
                .containsExactly("https://example.com/report/",
                        "https://second.example.org/source");
    }

    @Test
    void removesUrlsThatWereNotPresentInExecutionEvidence() {
        List<ResearchSource> sources = List.of(new ResearchSource("Official",
                "https://example.com/report", "", "", ""));

        ResearchSynthesisPolicy.Verification verification = policy.verify(
                "Verified https://example.com/report and invented https://fake.example/x.",
                sources);

        assertThat(verification.verified()).isFalse();
        assertThat(verification.report())
                .contains("https://example.com/report")
                .contains("[unverified source removed].")
                .doesNotContain("https://fake.example/x");
        assertThat(verification.unverifiedUrls())
                .containsExactly("https://fake.example/x");
    }

    @Test
    void doesNotTrustUrlsProducedByModelOnlySteps() {
        PlanStep modelStep = PlanStep.pending("draft", "draft", "llm",
                Map.of(), List.of()).completed("Model claim https://hallucinated.example/x");

        List<ResearchSource> sources = policy.collectSources(
                new AgentPlan("goal", List.of(modelStep)));

        assertThat(sources).isEmpty();
    }

    private static PlanStep completed(String id, String output) {
        return PlanStep.pending(id, id, "web_search", Map.of(), List.of()).completed(output);
    }
}
