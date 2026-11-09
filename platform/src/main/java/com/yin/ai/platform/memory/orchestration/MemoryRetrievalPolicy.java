package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.api.MemoryQuery;

public interface MemoryRetrievalPolicy {
    MemoryRetrievalPlan plan(MemoryQuery query, MemoryAccessContext context);
}
