package com.yin.ai.platform.core.memory.procedural;

import com.yin.ai.platform.core.memory.procedural.Procedure;
import com.yin.ai.platform.core.memory.procedural.ProcedureMatcher;
import com.yin.ai.platform.core.memory.procedural.ProcedureState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProcedureMatcher 默认实现")
class ProcedureMatcherTest {

    private ProcedureMatcher matcher;
    private List<Procedure> candidates;

    @BeforeEach
    void setUp() {
        matcher = new DefaultProcedureMatcher();
        candidates = List.of(
                Procedure.record("p1", "write unit tests for UserService")
                        .transitionTo(ProcedureState.VERIFIED).transitionTo(ProcedureState.ACTIVE)
                        .withTag("java").withTag("testing"),
                Procedure.record("p2", "deploy to production")
                        .transitionTo(ProcedureState.VERIFIED).transitionTo(ProcedureState.ACTIVE)
                        .withTag("deploy"),
                Procedure.record("p3", "write integration tests for API")
                        .transitionTo(ProcedureState.VERIFIED).transitionTo(ProcedureState.ACTIVE)
                        .withTag("java").withTag("testing"));
    }

    @Test
    @DisplayName("精确关键字匹配返回相关 Procedure")
    void shouldMatchExactKeywords() {
        List<Procedure> results = matcher.match("unit tests", candidates, 3);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).goal()).contains("unit tests");
    }

    @Test
    @DisplayName("标签匹配返回相关 Procedure")
    void shouldMatchByTag() {
        List<Procedure> results = matcher.match("java testing", candidates, 3);

        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(p -> p.tags().contains("java"));
    }

    @Test
    @DisplayName("topK 限制返回数量")
    void shouldLimitResults() {
        List<Procedure> results = matcher.match("tests", candidates, 2);
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("无匹配时返回空列表")
    void shouldReturnEmptyWhenNoMatch() {
        List<Procedure> results = matcher.match("machine learning model", candidates, 3);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("空候选列表返回空")
    void shouldReturnEmptyForEmptyCandidates() {
        List<Procedure> results = matcher.match("anything", List.of(), 3);
        assertThat(results).isEmpty();
    }

    // ==================== Default implementation ====================

    private static class DefaultProcedureMatcher implements ProcedureMatcher {
        @Override
        public List<Procedure> match(String goal, List<Procedure> candidates, int topK) {
            return candidates.stream()
                    .filter(p -> {
                        String lowerGoal = goal.toLowerCase();
                        String lowerProcGoal = p.goal().toLowerCase();
                        if (lowerProcGoal.contains(lowerGoal) || lowerGoal.contains(lowerProcGoal)) {
                            return true;
                        }
                        // Tag matching
                        return p.tags().stream().anyMatch(
                                tag -> lowerGoal.contains(tag.toLowerCase()));
                    })
                    .limit(topK)
                    .toList();
        }
    }
}
