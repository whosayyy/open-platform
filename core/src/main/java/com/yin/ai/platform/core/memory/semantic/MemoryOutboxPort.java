package com.yin.ai.platform.core.memory.semantic;

/** Transactional outbox port used by the MySQL source-of-truth transaction. */
public interface MemoryOutboxPort {
    void append(String eventId, String aggregateType, String aggregateId,
                int aggregateVersion, String eventType, String payloadJson);
}
