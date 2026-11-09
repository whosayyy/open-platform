package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.time.Instant;

/** Temporal semantic fact persisted in MySQL. */
public record SemanticFact(
        Long id, String tenantId, String userId, MemoryScope scope, String scopeId,
        String semanticKey, String subject, String predicate, String object,
        Instant validFrom, Instant validTo, double confidence, double importance,
        SemanticFactStatus status, String sourceType, String sourceId,
        String sourceSessionId, Long supersedesId, EmbeddingStatus embeddingStatus,
        int version, Instant createdAt, Instant updatedAt) {

    public SemanticFact {
        confidence = Math.clamp(confidence, 0.0, 1.0);
        importance = Math.clamp(importance, 0.0, 1.0);
        validFrom = validFrom != null ? validFrom : Instant.now();
        status = status != null ? status : SemanticFactStatus.ACTIVE;
        embeddingStatus = embeddingStatus != null ? embeddingStatus : EmbeddingStatus.PENDING;
        version = Math.max(1, version);
    }

    public boolean isEffectiveAt(Instant instant) {
        Instant at = instant != null ? instant : Instant.now();
        return status == SemanticFactStatus.ACTIVE
                && !validFrom.isAfter(at)
                && (validTo == null || validTo.isAfter(at));
    }

    public SemanticFact withConfidenceAndImportance(double newConfidence, double newImportance) {
        return new SemanticFact(id, tenantId, userId, scope, scopeId, semanticKey,
                subject, predicate, object, validFrom, validTo, newConfidence,
                newImportance, status, sourceType, sourceId, sourceSessionId,
                supersedesId, EmbeddingStatus.STALE, version + 1, createdAt, Instant.now());
    }
}
