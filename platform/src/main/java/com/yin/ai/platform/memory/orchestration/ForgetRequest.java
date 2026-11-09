package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.security.MemoryScope;

public record ForgetRequest(Mode mode, String memoryId, String sourceType,
                            String sourceId, MemoryScope scope, String scopeId,
                            String subject, String predicate, String idempotencyKey) {
    public enum Mode { MEMORY_ID, SOURCE, SCOPE, SUBJECT_PREDICATE }
}
