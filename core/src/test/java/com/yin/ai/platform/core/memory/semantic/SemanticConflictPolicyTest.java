package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.security.MemoryScope;
import com.yin.ai.platform.core.memory.semantic.SemanticConflictPolicy;
import com.yin.ai.platform.core.memory.semantic.SemanticFact;
import com.yin.ai.platform.core.memory.semantic.SemanticFactStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticConflictPolicyTest {
    private final SemanticConflictPolicy policy = new SemanticConflictPolicy();

    @Test
    void newerAgeSupersedesAndOnlyNewFactIsEffective() {
        SemanticFact age17 = fact(17L, "17", Instant.parse("2025-01-01T00:00:00Z"),
                0.9, "LLM_EXTRACTION", SemanticFactStatus.ACTIVE);
        SemanticFact age21 = fact(null, "21", Instant.parse("2026-01-01T00:00:00Z"),
                0.85, "USER_EXPLICIT", SemanticFactStatus.ACTIVE);

        assertThat(policy.decide(age17, age21))
                .isEqualTo(SemanticConflictPolicy.Decision.SUPERSEDE);
        SemanticFact superseded = fact(17L, "17", age17.validFrom(), 0.9,
                "LLM_EXTRACTION", SemanticFactStatus.SUPERSEDED);
        assertThat(superseded.isEffectiveAt(Instant.now())).isFalse();
        assertThat(age21.isEffectiveAt(Instant.now())).isTrue();
    }

    @Test
    void repeatedFactReinforcesWithoutSecondActiveIdentity() {
        SemanticFact first = fact(1L, "blue", Instant.now(), 0.6,
                "LLM_EXTRACTION", SemanticFactStatus.ACTIVE);
        SemanticFact repeated = fact(null, " blue. ", Instant.now(), 0.7,
                "USER_EXPLICIT", SemanticFactStatus.ACTIVE);

        assertThat(policy.decide(first, repeated))
                .isEqualTo(SemanticConflictPolicy.Decision.REINFORCE);
        assertThat(policy.reinforcedConfidence(0.6, 0.7)).isEqualTo(0.88);
    }

    private SemanticFact fact(Long id, String object, Instant observedAt, double confidence,
                              String source, SemanticFactStatus status) {
        return new SemanticFact(id, "tenant", "user", MemoryScope.USER, "user",
                "user|age", "user", "age", object, observedAt, null, confidence,
                0.8, status, source, "source", "session", null,
                EmbeddingStatus.PENDING, 1, observedAt, observedAt);
    }
}
