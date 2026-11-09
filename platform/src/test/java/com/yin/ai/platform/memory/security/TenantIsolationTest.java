package com.yin.ai.platform.memory.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantIsolation 租户隔离")
class TenantIsolationTest {

    private PrefixTenantIsolation isolation;

    @BeforeEach
    void setUp() {
        isolation = new PrefixTenantIsolation();
    }

    @Test
    @DisplayName("isolateKey 添加租户前缀")
    void shouldIsolateKey() {
        String result = isolation.isolateKey("t-123", "ai:memory:ep:user-1");
        assertThat(result).isEqualTo("tenant:t-123:ai:memory:ep:user-1");
    }

    @Test
    @DisplayName("null tenantId 返回原 key")
    void shouldReturnOriginalForNullTenant() {
        assertThat(isolation.isolateKey(null, "raw-key")).isEqualTo("raw-key");
        assertThat(isolation.isolateKey("  ", "raw-key")).isEqualTo("raw-key");
    }

    @Test
    @DisplayName("extractTenantId 从隔离 key 中提取")
    void shouldExtractTenantId() {
        String isolated = isolation.isolateKey("t-456", "key");
        assertThat(isolation.extractTenantId(isolated)).isEqualTo("t-456");
    }

    @Test
    @DisplayName("isSameTenant 判断同租户")
    void shouldDetectSameTenant() {
        String k1 = isolation.isolateKey("t-1", "key-a");
        String k2 = isolation.isolateKey("t-1", "key-b");
        String k3 = isolation.isolateKey("t-2", "key-c");

        assertThat(isolation.isSameTenant(k1, k2)).isTrue();
        assertThat(isolation.isSameTenant(k1, k3)).isFalse();
    }

    @Test
    @DisplayName("stripTenantPrefix 移除前缀恢复原 key")
    void shouldStripPrefix() {
        String isolated = isolation.isolateKey("t-789", "ai:memory:ep:user-1");
        String stripped = isolation.stripTenantPrefix(isolated);
        assertThat(stripped).isEqualTo("ai:memory:ep:user-1");
    }
}
