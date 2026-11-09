package com.yin.ai.platform.memory.security;

import com.yin.ai.platform.core.memory.security.MemoryScope;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.multiagent.AccessControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemorySecurityManager")
class MemorySecurityManagerTest {

    private MemorySecurityManager securityManager;
    private PrefixTenantIsolation tenantIsolation;

    @BeforeEach
    void setUp() {
        tenantIsolation = new PrefixTenantIsolation();
        AccessControl accessControl = new AccessControl() {
            @Override
            public boolean checkReadAccess(MemoryId memoryId, String agentId) {
                return "agent-a".equals(agentId) || "admin".equals(agentId);
            }

            @Override
            public boolean checkWriteAccess(MemoryId memoryId, String agentId) {
                return "agent-a".equals(agentId);
            }

            @Override
            public boolean checkAdminAccess(String agentId) {
                return "admin".equals(agentId);
            }
        };
        securityManager = new MemorySecurityManager(tenantIsolation, accessControl);
    }

    @Test
    @DisplayName("resolveKey 生成租户隔离 key")
    void shouldResolveKey() {
        MemoryId id = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");
        String key = securityManager.resolveKey(id, "t-100");
        assertThat(key).startsWith("tenant:t-100:");
        assertThat(key).endsWith("ep-1");
    }

    @Test
    @DisplayName("resolveScopedKey 生成作用域 + 租户隔离 key")
    void shouldResolveScopedKey() {
        MemoryId id = MemoryId.of("task-1", MemoryType.EPISODIC, "user-1");
        String key = securityManager.resolveScopedKey(id, MemoryScope.SESSION,
                "sess-123", "t-200");
        assertThat(key).startsWith("tenant:t-200:session:sess-123:");
    }

    @Test
    @DisplayName("authorizeRead 检查 Agent 读权限")
    void shouldAuthorizeRead() {
        MemoryId id = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");
        assertThat(securityManager.authorizeRead(id, "agent-a", "t-1")).isTrue();
        assertThat(securityManager.authorizeRead(id, "admin", "t-1")).isTrue();
        assertThat(securityManager.authorizeRead(id, "agent-b", "t-1")).isFalse();
    }

    @Test
    @DisplayName("authorizeWrite 检查 Agent 写权限")
    void shouldAuthorizeWrite() {
        MemoryId id = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");
        assertThat(securityManager.authorizeWrite(id, "agent-a", "t-1")).isTrue();
        assertThat(securityManager.authorizeWrite(id, "admin", "t-1")).isFalse();
    }

    @Test
    @DisplayName("authorizeAdmin 检查管理权限")
    void shouldAuthorizeAdmin() {
        assertThat(securityManager.authorizeAdmin("admin")).isTrue();
        assertThat(securityManager.authorizeAdmin("agent-a")).isFalse();
    }
}
