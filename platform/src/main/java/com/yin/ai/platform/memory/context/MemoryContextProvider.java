package com.yin.ai.platform.memory.context;

public interface MemoryContextProvider {
    ContextFragment provide(ContextRequest request);
}
