package com.yin.ai.platform.memory.context;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;

public record ContextRequest(MemoryQuery query, MemoryAccessContext accessContext,
                             int tokenBudget) {}
