package com.yin.ai.platform.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryCell")
class MemoryCellTest {

    @Test
    @DisplayName("create with minimum fields")
    void createMinimal() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("cell-1"), MemoryType.EPISODIC, "hello world");
        assertThat(cell.cellId().id()).isEqualTo("cell-1");
        assertThat(cell.memoryType()).isEqualTo(MemoryType.EPISODIC);
        assertThat(cell.content()).isEqualTo("hello world");
        assertThat(cell.version()).isEqualTo(1);
        assertThat(cell.vector()).isEmpty();
        assertThat(cell.attributes()).isEmpty();
    }

    @Test
    @DisplayName("immutability: withVersion returns new instance")
    void withVersionImmutability() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("cell-1"), MemoryType.EPISODIC, "hello");
        MemoryCell updated = cell.withVersion(2);
        assertThat(cell.version()).isEqualTo(1);
        assertThat(updated.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("immutability: withTag does not modify original")
    void withTagImmutability() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("cell-1"), MemoryType.EPISODIC, "hello");
        MemoryCell tagged = cell.withTag("key", "value");
        assertThat(cell.tags()).isEmpty();
        assertThat(tagged.tags()).containsEntry("key", "value");
    }

    @Test
    @DisplayName("withVector attaches embedding")
    void withVector() {
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("cell-1"), MemoryType.SEMANTIC, "fact");
        MemoryVector vec = MemoryVector.of(new float[]{1.0f, 0.0f, 0.0f}, "test-model");
        MemoryCell embedded = cell.withVector(vec);
        assertThat(embedded.vector()).isPresent();
        assertThat(embedded.vector().get().dimension()).isEqualTo(3);
    }

    @Test
    @DisplayName("null content defaults to empty string")
    void nullContentDefaults() {
        MemoryCell cell = new MemoryCell(MemoryCellId.of("c1"), MemoryType.WORKING, null,
                Map.of(), null, 0, Optional.empty(), Map.of(), null, 1);
        assertThat(cell.content()).isEqualTo("");
    }
}
