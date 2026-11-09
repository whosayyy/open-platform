package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port; implementations own transactions and row locking. */
public interface SemanticFactRepository {
    Optional<SemanticFact> findActiveForUpdate(String tenantId, MemoryScope scope,
                                               String scopeId, String semanticKey);
    Optional<SemanticFact> findById(long id);
    List<SemanticFact> findEffectiveByIds(String tenantId, List<Long> ids, Instant at);
    List<SemanticFact> searchEffective(String tenantId, String userId, String text,
                                       int limit, Instant at);
    SemanticFact insert(SemanticFact fact);
    SemanticFact reinforce(long factId, double confidence, double importance, int expectedVersion);
    SemanticFact supersede(long oldFactId, SemanticFact replacement, Instant effectiveAt,
                           int expectedVersion);
    int updateEmbeddingStatus(long factId, EmbeddingStatus status, int aggregateVersion);
    int markDeletedByIds(String tenantId, List<Long> ids, Instant deletedAt);
}
