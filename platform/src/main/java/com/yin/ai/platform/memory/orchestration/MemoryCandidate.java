package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.time.Instant;
import java.util.Map;

/** Untrusted observation submitted to the memory write policy. */
public record MemoryCandidate(
        String content, String sourceType, String sourceId,
        MemoryType suggestedType, MemoryScope suggestedScope,
        String subject, String predicate, String object,
        Instant observedAt, Map<String, Object> metadata) {

    public MemoryCandidate {
        content = content != null ? content.trim() : "";
        observedAt = observedAt != null ? observedAt : Instant.now();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static MemoryCandidate of(String content, String sourceType, String sourceId) {
        return new MemoryCandidate(content, sourceType, sourceId, null, null,
                null, null, null, Instant.now(), Map.of());
    }
}
