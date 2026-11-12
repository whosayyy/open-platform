package com.yin.ai.platform.memory.api;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMemoryRuntimeMinScoreTest {

    private final InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
    private final ElasticsearchMemorySearchService es = mock(ElasticsearchMemorySearchService.class);
    private DefaultMemoryRuntime runtime;
    private final MemoryId id = MemoryId.of("semantic-7", MemoryType.SEMANTIC, "7");
    private final MemoryContext ctx = new MemoryContext(null, null, 7L, "t", "test",
            Instant.now(), Map.of());

    @BeforeEach
    void setUp() {
        runtime = new DefaultMemoryRuntime(new DefaultWorkingMemoryManager(store), store);
        runtime.setEsSearchService(es);
    }

    @Test
    void esPathFiltersBelowMinScore() {
        store.append(id, MemoryCell.of(MemoryCellId.of("c1"), MemoryType.SEMANTIC, "天气很好"));
        ScoredMemoryCell high = ScoredMemoryCell.of(
                store.readAll(id).get(0), 12.5, MemoryType.SEMANTIC);
        ScoredMemoryCell low = ScoredMemoryCell.of(
                store.readAll(id).get(0), 3.0, MemoryType.SEMANTIC);
        when(es.search(any(), anyString())).thenReturn(List.of(high, low));

        QueryResult result = runtime.retrieve(query("天气", 5.0), ctx);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).score()).isEqualTo(12.5);
    }

    @Test
    void fallbackPathReturnsEmptyWhenMinScoreAboveConstant() {
        // fallback 恒分 0.8：minScore > 0.8 时直接空结果
        store.append(id, MemoryCell.of(MemoryCellId.of("c1"), MemoryType.SEMANTIC, "包含关键词的内容"));
        when(es.search(any(), anyString())).thenReturn(List.of());

        QueryResult result = runtime.retrieve(query("关键词", 0.9), ctx);

        assertThat(result.results()).isEmpty();
    }

    @Test
    void defaultMinScoreZeroKeepsAllResults() {
        // 经 runtime.append 写入以注册 memoryIdRegistry（fallback 遍历依赖 registry）
        runtime.append(id, "包含关键词的内容", ctx);
        when(es.search(any(), anyString())).thenReturn(List.of());

        QueryResult result = runtime.retrieve(MemoryQuery.byText("关键词", 10), ctx);

        // 默认 minScore=0，fallback 命中 1 条
        assertThat(result.results()).hasSize(1);
    }

    private MemoryQuery query(String text, double minScore) {
        return new MemoryQuery(text, null, List.of(), null, List.of(), 10, minScore, Map.of());
    }
}
