package com.yin.ai.platform.core.memory.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryScope 作用域层级")
class MemoryScopeTest {

    @Test
    @DisplayName("GLOBAL 是所有作用域的祖先")
    void globalIsAncestorOfAll() {
        assertThat(MemoryScope.GLOBAL.isAncestorOf(MemoryScope.TENANT)).isTrue();
        assertThat(MemoryScope.GLOBAL.isAncestorOf(MemoryScope.STEP)).isTrue();
    }

    @Test
    @DisplayName("STEP 是任意祖先的子作用域")
    void stepIsChildOfAll() {
        assertThat(MemoryScope.STEP.isChildOf(MemoryScope.GLOBAL)).isTrue();
        assertThat(MemoryScope.STEP.isChildOf(MemoryScope.SESSION)).isTrue();
    }

    @Test
    @DisplayName("同级作用域不是子作用域")
    void sameLevelNotChild() {
        assertThat(MemoryScope.TASK.isChildOf(MemoryScope.WORKFLOW)).isFalse();
        assertThat(MemoryScope.WORKFLOW.isChildOf(MemoryScope.TASK)).isFalse();
    }

    @Test
    @DisplayName("isolate 生成带前缀的 key")
    void shouldIsolateKey() {
        String isolated = MemoryScope.SESSION.isolate("sess-123", "ai:memory:ep:user-1");
        assertThat(isolated).startsWith("session:");
        assertThat(isolated).contains("sess-123");
    }

    @Test
    @DisplayName("extractScopeId 提取作用域 ID")
    void shouldExtractScopeId() {
        String isolated = MemoryScope.TENANT.isolate("t-456", "raw-key");
        String tenantId = MemoryScope.extractScopeId(isolated, MemoryScope.TENANT);
        assertThat(tenantId).isEqualTo("t-456");
    }
}
