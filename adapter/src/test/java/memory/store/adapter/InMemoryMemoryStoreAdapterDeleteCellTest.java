package com.yin.ai.platform.memory.store.adapter;

import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMemoryStoreAdapterDeleteCellTest {

    private final InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
    private final MemoryId id = MemoryId.of("episodic-u1", MemoryType.EPISODIC, "u1");

    @Test
    void deleteCellRemovesOnlyTargetCell() {
        MemoryCell c1 = store.append(id, MemoryCell.of(MemoryCellId.of("a"), MemoryType.EPISODIC, "one"));
        MemoryCell c2 = store.append(id, MemoryCell.of(MemoryCellId.of("b"), MemoryType.EPISODIC, "two"));
        MemoryCell c3 = store.append(id, MemoryCell.of(MemoryCellId.of("c"), MemoryType.EPISODIC, "three"));

        boolean removed = store.deleteCell(id, MemoryCellId.of("b"));

        assertThat(removed).isTrue();
        List<MemoryCell> remaining = store.readAll(id);
        assertThat(remaining).extracting(c -> c.cellId().id()).containsExactly("a", "c");
        assertThat(remaining).extracting(MemoryCell::content).containsExactly("one", "three");
    }

    @Test
    void deleteCellReturnsFalseForMissingCell() {
        store.append(id, MemoryCell.of(MemoryCellId.of("a"), MemoryType.EPISODIC, "one"));

        boolean removed = store.deleteCell(id, MemoryCellId.of("missing"));

        assertThat(removed).isFalse();
        assertThat(store.readAll(id)).hasSize(1);
    }

    @Test
    void deleteLastCellRemovesMapping() {
        store.append(id, MemoryCell.of(MemoryCellId.of("a"), MemoryType.EPISODIC, "one"));

        boolean removed = store.deleteCell(id, MemoryCellId.of("a"));

        assertThat(removed).isTrue();
        assertThat(store.size(id)).isZero();
        assertThat(store.readAll(id)).isEmpty();
    }

    @Test
    void concurrentAppendsSurviveDeleteCell() throws Exception {
        MemoryId other = MemoryId.of("episodic-u2", MemoryType.EPISODIC, "u2");
        store.append(other, MemoryCell.of(MemoryCellId.of("keep"), MemoryType.EPISODIC, "keep"));

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                store.append(id, MemoryCell.of(MemoryCellId.of("w" + i), MemoryType.EPISODIC, "w" + i));
            }
        });
        writer.start();
        store.deleteCell(id, MemoryCellId.of("missing"));
        writer.join(5000);

        assertThat(store.readAll(other)).extracting(c -> c.cellId().id()).containsExactly("keep");
    }
}
