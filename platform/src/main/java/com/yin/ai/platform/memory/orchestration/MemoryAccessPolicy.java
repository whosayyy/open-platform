package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.security.MemoryScope;

public interface MemoryAccessPolicy {
    AccessDecision authorizeRead(MemoryScope scope, String scopeId,
                                 String tenantId, String userId,
                                 MemoryAccessContext context);
    AccessDecision authorizeWrite(MemoryScope scope, String scopeId,
                                  String tenantId, String userId,
                                  MemoryAccessContext context);

    record AccessDecision(boolean allowed, String reason) {
        public static AccessDecision allow() { return new AccessDecision(true, "allowed"); }
        public static AccessDecision deny(String reason) { return new AccessDecision(false, reason); }
    }
}
