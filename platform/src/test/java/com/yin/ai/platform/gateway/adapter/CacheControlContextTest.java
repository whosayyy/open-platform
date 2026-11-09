package com.yin.ai.platform.gateway.adapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheControlContext — ThreadLocal 上下文传递")
class CacheControlContextTest {

    @AfterEach
    void tearDown() {
        CacheControlContext.clear();
    }

    @Test
    @DisplayName("默认未启用")
    void shouldNotBeEnabledByDefault() {
        assertThat(CacheControlContext.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("设置后读取成功")
    void shouldReadAfterSet() {
        CacheControlContext.set(true);
        assertThat(CacheControlContext.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("清除后返回 false")
    void shouldReturnFalseAfterClear() {
        CacheControlContext.set(true);
        CacheControlContext.clear();
        assertThat(CacheControlContext.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("set(false) 后返回 false")
    void setFalseShouldReturnFalse() {
        CacheControlContext.set(false);
        assertThat(CacheControlContext.isEnabled()).isFalse();
    }
}
