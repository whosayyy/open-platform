package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.MemoryId;

import java.util.List;

public record ConsolidationRequest(MemoryId sourceMemoryId, List<String> sourceIds,
                                   MemoryAccessContext context, boolean sessionEnded,
                                   boolean successfulTask) {
    public ConsolidationRequest {
        sourceIds = sourceIds != null ? List.copyOf(sourceIds) : List.of();
    }

    public ConsolidationRequest(MemoryId sourceMemoryId, List<String> sourceIds,
                                MemoryAccessContext context, boolean sessionEnded) {
        this(sourceMemoryId, sourceIds, context, sessionEnded, false);
    }
}
