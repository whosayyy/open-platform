package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryPolicyTest {
    private final MemoryAccessContext context = new MemoryAccessContext(
            "tenant-1", "user-1", "session-1", "task-1", "workflow-1",
            "agent-1", "step-1", Set.of("USER"), Set.of(), "trace-1");

    @Test
    void rejectsAcknowledgementsAndRuntimeState() {
        DefaultMemoryWritePolicy policy = new DefaultMemoryWritePolicy();
        assertThat(policy.evaluate(MemoryCandidate.of("thanks", "USER", "m1"), context)
                .shouldRemember()).isFalse();
        MemoryCandidate runtime = new MemoryCandidate("checkpoint", "AGENT", "m2", null,
                null, null, null, null, Instant.now(), Map.of("runtimeType", "Checkpoint"));
        assertThat(policy.evaluate(runtime, context).shouldRemember()).isFalse();
    }

    @Test
    void normalizesSessionToEpisodicScope() {
        MemoryCandidate candidate = new MemoryCandidate("remember I prefer concise answers",
                "SESSION_USER", "m1", MemoryType.SESSION, MemoryScope.SESSION,
                null, null, null, Instant.now(), Map.of());
        MemoryWriteDecision decision = new DefaultMemoryWritePolicy().evaluate(candidate, context);
        assertThat(decision.type()).isEqualTo(MemoryType.EPISODIC);
        assertThat(decision.scope()).isEqualTo(MemoryScope.SESSION);
        assertThat(decision.scopeId()).isEqualTo("session-1");
    }

    @Test
    void deniesCrossTenantUserAndScope() {
        DefaultMemoryAccessPolicy policy = new DefaultMemoryAccessPolicy();
        assertThat(policy.authorizeRead(MemoryScope.USER, "user-1", "tenant-2",
                "user-1", context).allowed()).isFalse();
        assertThat(policy.authorizeRead(MemoryScope.SESSION, "session-2", "tenant-1",
                "user-1", context).allowed()).isFalse();
        assertThat(policy.authorizeWrite(MemoryScope.GLOBAL, null, "tenant-1",
                "user-1", context).allowed()).isFalse();
    }

    @Test
    void usesOptionalClassifierOnlyForAmbiguousCandidates() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        MemoryCandidateClassifier classifier = (candidate, access) -> {
            calls.incrementAndGet();
            return java.util.Optional.of(new MemoryWriteDecision(true, MemoryType.PROCEDURAL,
                    MemoryScope.TASK, access.taskId(), 0.7, 0.6,
                    java.time.Duration.ofDays(365), "classified"));
        };
        DefaultMemoryWritePolicy policy = new DefaultMemoryWritePolicy(classifier);

        assertThat(policy.evaluate(MemoryCandidate.of("execute the familiar workflow", "AGENT", "m3"), context)
                .type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(policy.evaluate(MemoryCandidate.of("记住我喜欢简洁回答", "USER", "m4"), context)
                .type()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(calls).hasValue(1);
    }
}
