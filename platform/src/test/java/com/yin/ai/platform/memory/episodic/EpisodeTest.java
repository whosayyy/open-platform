package com.yin.ai.platform.memory.episodic;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Episode 模型")
class EpisodeTest {

    private final MemoryId memoryId = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");

    @Test
    @DisplayName("创建 Episode 并访问字段")
    void shouldCreateEpisode() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T01:00:00Z");
        Episode episode = Episode.of("ep-1", memoryId, "Test Episode",
                EpisodeType.TASK, from, to, List.of("cell-1", "cell-2"));

        assertThat(episode.id()).isEqualTo("ep-1");
        assertThat(episode.memoryId()).isEqualTo(memoryId);
        assertThat(episode.title()).isEqualTo("Test Episode");
        assertThat(episode.type()).isEqualTo(EpisodeType.TASK);
        assertThat(episode.spanFrom()).isEqualTo(from);
        assertThat(episode.spanTo()).isEqualTo(to);
        assertThat(episode.cellIds()).containsExactly("cell-1", "cell-2");
        assertThat(episode.cellCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("null cellIds 和 metadata 使用空集合")
    void shouldHandleNullCollections() {
        Episode episode = new Episode("ep-1", memoryId, "Test", "",
                EpisodeType.CONVERSATION, Instant.now(), Instant.now(), null, null);

        assertThat(episode.cellIds()).isEmpty();
        assertThat(episode.metadata()).isEmpty();
    }

    @Test
    @DisplayName("withSummary 返回新实例并更新 summary")
    void shouldUpdateSummary() {
        Episode episode = Episode.of("ep-1", memoryId, "Title",
                EpisodeType.CONVERSATION, Instant.now(), Instant.now(), List.of());

        Episode updated = episode.withSummary("This is a summary.");

        assertThat(updated.summary()).isEqualTo("This is a summary.");
        assertThat(updated.id()).isEqualTo(episode.id());
        assertThat(updated.title()).isEqualTo(episode.title());
    }

    @Test
    @DisplayName("withMetadata 追加元数据")
    void shouldAddMetadata() {
        Episode episode = Episode.of("ep-1", memoryId, "Title",
                EpisodeType.TASK, Instant.now(), Instant.now(), List.of());

        Episode updated = episode.withMetadata("priority", "high");

        assertThat(updated.metadata()).containsEntry("priority", "high");
        assertThat(episode.metadata()).isEmpty(); // original unchanged
    }

    @Test
    @DisplayName("EpisodeType 枚举值完整")
    void shouldHaveAllEpisodeTypes() {
        assertThat(EpisodeType.values()).contains(
                EpisodeType.CONVERSATION,
                EpisodeType.TASK,
                EpisodeType.WORKFLOW_STEP,
                EpisodeType.CUSTOM);
    }
}
