package com.yin.ai.platform.memory.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("MemoryVersion")
class MemoryVersionTest {

    @Test
    @DisplayName("v1 工厂方法")
    void v1Factory() {
        MemoryVersion v1 = MemoryVersion.v1();
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.description()).contains("Initial");
    }

    @Test
    @DisplayName("of 工厂方法")
    void shouldCreateWithFactory() {
        MemoryVersion v = MemoryVersion.of(2, "Added vector field");
        assertThat(v.version()).isEqualTo(2);
        assertThat(v.description()).isEqualTo("Added vector field");
        assertThat(v.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("版本比较")
    void shouldCompareVersions() {
        MemoryVersion v1 = MemoryVersion.v1();
        MemoryVersion v2 = MemoryVersion.of(2, "v2");

        assertThat(v2.isNewerThan(v1)).isTrue();
        assertThat(v1.isOlderThan(v2)).isTrue();
        assertThat(v1.isNewerThan(v2)).isFalse();
        assertThat(v2.isOlderThan(v1)).isFalse();
    }

    @Test
    @DisplayName("同版本比较")
    void sameVersionNotNewerOrOlder() {
        MemoryVersion v1 = MemoryVersion.v1();
        MemoryVersion alsoV1 = MemoryVersion.of(1, "duplicate");

        assertThat(v1.isNewerThan(alsoV1)).isFalse();
        assertThat(v1.isOlderThan(alsoV1)).isFalse();
    }

    @Test
    @DisplayName("空 description 抛出异常")
    void shouldRejectBlankDescription() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryVersion.of(1, "  "));
    }

    @Test
    @DisplayName("createdAt 默认值为当前时间")
    void defaultsCreatedAt() {
        Instant before = Instant.now();
        MemoryVersion v = MemoryVersion.of(1, "test");
        assertThat(v.createdAt()).isAfterOrEqualTo(before);
    }
}
