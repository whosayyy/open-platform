package com.yin.ai.platform.memory.cache;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TieredMemoryStoreDeleteCellTest {

    private final InMemoryMemoryStoreAdapter delegate = new InMemoryMemoryStoreAdapter();
    private final TieredMemoryStore store = new TieredMemoryStore(delegate);
    private final MemoryId id = MemoryId.of("episodic-u1", MemoryType.EPISODIC, "u1");

    @Test
    void deleteCellInvalidatesL1ListCache() {
        store.append(id, MemoryCell.of(MemoryCellId.of("a"), MemoryType.EPISODIC, "one"));
        store.append(id, MemoryCell.of(MemoryCellId.of("b"), MemoryType.EPISODIC, "two"));
        // 预热 L1 列表缓存
        assertThat(store.readTimeline(id, 10)).hasSize(2);

        boolean removed = store.deleteCell(id, MemoryCellId.of("a"));

        assertThat(removed).isTrue();
        // L1 已失效：再次读取必须反映删除结果
        List<MemoryCell> after = store.readTimeline(id, 10);
        assertThat(after).extracting(c -> c.cellId().id()).containsExactly("b");
    }

    @Test
    void deleteCellDoesNotInvalidateOtherOwnersCells() {
        MemoryId other = MemoryId.of("episodic-u2", MemoryType.EPISODIC, "u2");
        store.append(other, MemoryCell.of(MemoryCellId.of("x"), MemoryType.EPISODIC, "other"));

        store.deleteCell(id, MemoryCellId.of("missing"));

        assertThat(store.readAll(other)).extracting(c -> c.cellId().id()).containsExactly("x");
    }
}
