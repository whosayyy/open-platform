package com.yin.ai.platform.memory.api;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.MemoryVector;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryRuntimeHybridRetrievalTest {

    private DefaultMemoryRuntime runtime;
    private MemoryContext context;

    @BeforeEach
    void setUp() {
        InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
        runtime = new DefaultMemoryRuntime(new DefaultWorkingMemoryManager(store), store);
        runtime.setEmbeddingService(text -> {
            String normalized = text.toLowerCase();
            return MemoryVector.of(normalized.contains("feline") || normalized.contains("cat")
                    ? new float[]{1, 0} : new float[]{0, 1}, "deterministic-test");
        });
        context = new MemoryContext("trace", null, 1L, "agent", "test",
                Instant.now(), Map.of());
    }

    @Test
    void recallsSemanticMatchWhenKeywordDoesNotMatch() {
        runtime.append(MemoryId.of("m1", MemoryType.SEMANTIC, "1"),
                "user prefers cat-related articles", context);
        runtime.append(MemoryId.of("m2", MemoryType.SEMANTIC, "1"),
                "database maintenance window", context);

        QueryResult result = runtime.retrieve(MemoryQuery.byText("feline preference", 5), context);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().cell().content()).contains("cat-related");
        assertThat(result.results().getFirst().explanation()).contains("VECTOR");
        assertThat(result.results().getFirst().score()).isBetween(0.8, 1.0);
    }

    @Test
    void excludesExpiredMemoryBeforeReturningContext() {
        runtime.append(MemoryId.of("expired", MemoryType.SEMANTIC, "1"),
                "feline preference is temporary",
                Map.of("validUntil", Instant.now().minusSeconds(60).toString()), context);

        QueryResult result = runtime.retrieve(MemoryQuery.byText("feline preference", 5), context);

        assertThat(result.results()).isEmpty();
    }
}
