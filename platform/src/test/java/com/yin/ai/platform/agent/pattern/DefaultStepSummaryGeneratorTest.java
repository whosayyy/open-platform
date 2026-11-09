package com.yin.ai.platform.agent.pattern;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStepSummaryGeneratorTest {

    private final DefaultStepSummaryGenerator generator = new DefaultStepSummaryGenerator();

    @Test
    void convertsSearchJsonToReadableMarkdownWithoutLeakingArray() {
        String raw = """
                {"query":"topic","results":[
                  {"title":"权威资料","url":"https://example.test/a","source":"示例来源","content":"已经核准的事实摘要"}
                ]}
                """;
        StepResult result = new StepResult("t:s:1", "s", true, Map.of(), null,
                List.of(), List.of(), null, null, Instant.now());

        StepSummary summary = generator.generate(
                TaskStep.pending("s", "联网核准资料", "web_search", List.of()),
                result, raw, "撰写初稿");

        assertThat(summary.content()).contains("权威资料", "已经核准的事实摘要", "https://example.test/a")
                .doesNotContain("\"results\"", "\"query\"");
    }

    @Test
    void malformedJsonLookingOutputIsNeverReflectedToUser() {
        assertThat(generator.toReadableMarkdown("{\"results\":[broken" )).isEmpty();
    }
}
