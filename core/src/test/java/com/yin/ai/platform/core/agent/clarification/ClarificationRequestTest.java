package com.yin.ai.platform.core.agent.clarification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationRequestTest {

    @Test
    void roundTripsAsPortableScalarCollections() {
        ClarificationRequest request = new ClarificationRequest(
                "clarification-1", "task-1", "Research AI",
                List.of("Which region?"),
                Map.of("Which region?", List.of("China", "Global")),
                "Region changes the result", Instant.parse("2026-08-21T00:00:00Z"));

        ClarificationRequest restored = ClarificationRequest.fromPortableMap(
                request.toPortableMap());

        assertThat(restored).isEqualTo(request);
        assertThat(restored.toPortableMap().toString())
                .doesNotContain("ClarificationRequest", "com.yin");
    }

    @Test
    void readsLegacyQuestionKeysPrefixedKeysAndGenericMessage() {
        ClarificationRequest twoQuestions = new ClarificationRequest(null, "task-1",
                "Research AI", List.of("Region?", "Period?"), Map.of(), "", null);
        ClarificationRequest oneQuestion = new ClarificationRequest(null, "task-2",
                "Research AI", List.of("Region?"), Map.of(), "", null);

        assertThat(twoQuestions.answersFrom(Map.of(
                "Region?", "China",
                ClarificationRequest.ANSWER_PREFIX + "Period?", "2026")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "Region?", "China", "Period?", "2026"));
        assertThat(oneQuestion.answersFrom(Map.of("user_response", "Global")))
                .containsEntry("Region?", "Global");
    }
}
