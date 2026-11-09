package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.RelationType;

public interface MemoryLineagePort {
    void append(String tenantId, String sourceType, String sourceId,
                String targetType, String targetId, RelationType relationType);
}
