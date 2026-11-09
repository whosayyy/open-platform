package com.yin.ai.platform.memory.config;

import com.yin.ai.platform.core.memory.store.StoreType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MemoryProperties")
class MemoryPropertiesTest {

    @Test
    @DisplayName("默认值合理")
    void shouldHaveReasonableDefaults() {
        MemoryProperties props = new MemoryProperties();

        assertThat(props.getL1().getMaxSize()).isEqualTo(10_000);
        assertThat(props.getL1().getTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getL1().isRecordStats()).isTrue();

        assertThat(props.getL2().getKeyPrefix()).isEqualTo("ai:memory:");
        assertThat(props.effectiveL2Ttl()).isEqualTo(Duration.ofHours(1));
        assertThat(props.effectiveWorkingTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(props.effectiveSessionTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.effectiveEpisodicTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.getRetention().getCheckpointTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(props.getL2().isWriteThrough()).isTrue();
        assertThat(props.getL2().isCacheNullValues()).isTrue();

        assertThat(props.getStore().getDefaultType()).isEqualTo(StoreType.IN_MEMORY);
        assertThat(props.getStore().isCasEnabled()).isFalse();
        assertThat(props.getStore().getCasMaxRetries()).isEqualTo(3);

        assertThat(props.getMigration().getCurrentVersion()).isEqualTo(1);
        assertThat(props.getMigration().isAutoMigrate()).isFalse();
    }

    @Test
    @DisplayName("属性字段可修改")
    void shouldAllowMutation() {
        MemoryProperties props = new MemoryProperties();
        props.getL1().setMaxSize(5000);
        props.getStore().setCasEnabled(true);
        props.getMigration().setAutoMigrate(true);

        assertThat(props.getL1().getMaxSize()).isEqualTo(5000);
        assertThat(props.getStore().isCasEnabled()).isTrue();
        assertThat(props.getMigration().isAutoMigrate()).isTrue();
    }

    @Test
    void durationPropertiesWinAndLegacySecondsRemainSupported() {
        MemoryProperties props = new MemoryProperties();
        props.getRedis().setTtlSeconds(120L);
        assertThat(props.effectiveEpisodicTtl()).isEqualTo(Duration.ofSeconds(120));
        props.getRetention().setEpisodicTtl(Duration.ofDays(2));
        assertThat(props.effectiveEpisodicTtl()).isEqualTo(Duration.ofDays(2));

        MemoryProperties l2 = new MemoryProperties();
        l2.getL2().setDefaultTtlSeconds(90L);
        assertThat(l2.effectiveL2Ttl()).isEqualTo(Duration.ofSeconds(90));
        l2.getL2().setDefaultTtl(Duration.ofMinutes(3));
        assertThat(l2.effectiveL2Ttl()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void l1CannotOutliveL2() {
        MemoryProperties props = new MemoryProperties();
        props.getL1().setTtl(Duration.ofHours(2));
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
