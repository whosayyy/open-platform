package com.yin.ai.platform.memory.orchestration;

public interface MemoryForgetService {
    ForgetResult forget(ForgetRequest request, MemoryAccessContext context);
}
