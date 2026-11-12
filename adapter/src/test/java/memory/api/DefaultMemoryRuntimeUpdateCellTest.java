package com.yin.ai.platform.memory.api;

import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.search.ElasticsearchMemorySearchService;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DefaultMemoryRuntimeUpdateCellTest {

    private final InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
    private final ElasticsearchMemorySearchService es = mock(ElasticsearchMemorySearchService.class);
    private DefaultMemoryRuntime runtime;
    private final MemoryId id = MemoryId.of("semantic-7", MemoryType.SEMANTIC, "7");

    @BeforeEach
    void setUp() {
        runtime = new DefaultMemoryRuntime(new DefaultWorkingMemoryManager(store), store);
        runtime.setEsSearchService(es);
    }

    @Test
    void updateCellUpdatesExistingContentAndWritesThroughToEs() {
        MemoryCell cell = store.append(id, MemoryCell.of(MemoryCellId.of("f1"), MemoryType.SEMANTIC, "旧内容"));
        MemoryContext ctx = new MemoryContext(null, null, 7L, "creator", "fact.edit",
                Instant.now(), Map.of());

        boolean updated = runtime.updateCell(id, MemoryCellId.of("f1"), "新内容", ctx);

        assertThat(updated).isTrue();
        assertThat(store.readAll(id)).extracting(MemoryCell::content).containsExactly("新内容");
        // ES write-through：同 doc id（ownerKey_cellId）幂等 upsert
        verify(es).indexCell(eq(id), any(MemoryCell.class));
    }

    @Test
    void updateCellReturnsFalseForMissingCell() {
        MemoryContext ctx = new MemoryContext(null, null, 7L, "creator", "fact.edit",
                Instant.now(), Map.of());

        boolean updated = runtime.updateCell(id, MemoryCellId.of("ghost"), "内容", ctx);

        assertThat(updated).isFalse();
        verify(es, never()).indexCell(any(), any());
    }

    @Test
    void updateCellRejectsBlankOrNullContent() {
        store.append(id, MemoryCell.of(MemoryCellId.of("f1"), MemoryType.SEMANTIC, "旧"));
        MemoryContext ctx = new MemoryContext(null, null, 7L, "creator", "fact.edit",
                Instant.now(), Map.of());

        assertThat(runtime.updateCell(id, MemoryCellId.of("f1"), null, ctx)).isFalse();
        assertThat(runtime.updateCell(null, MemoryCellId.of("f1"), "x", ctx)).isFalse();
        assertThat(runtime.updateCell(id, null, "x", ctx)).isFalse();
    }
}
