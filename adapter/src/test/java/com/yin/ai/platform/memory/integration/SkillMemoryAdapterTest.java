package com.yin.ai.platform.memory.integration;

import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SkillMemoryAdapter")
class SkillMemoryAdapterTest {

    private SkillMemoryAdapter adapter;
    private MemoryContext ctx;

    @BeforeEach
    void setUp() {
        InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
        DefaultWorkingMemoryManager wmManager = new DefaultWorkingMemoryManager(store);
        DefaultMemoryRuntime runtime = new DefaultMemoryRuntime(wmManager, store);
        adapter = new SkillMemoryAdapter(runtime);
        ctx = MemoryContext.empty().withAgentId("skill-test");
    }

    @Test
    @DisplayName("createWorkingMemory 创建新的 Working Memory")
    void shouldCreateWorkingMemory() {
        WorkingMemory wm = adapter.createWorkingMemory(ctx);
        assertThat(wm).isNotNull();
        assertThat(wm.getId().type()).isEqualTo(MemoryType.WORKING);
    }

    @Test
    @DisplayName("save + load WorkingMemory")
    void shouldSaveAndLoadWorkingMemory() {
        WorkingMemory wm = adapter.createWorkingMemory(ctx);
        adapter.saveWorkingMemory(wm);

        Optional<WorkingMemory> loaded = adapter.loadWorkingMemory(wm.getId());
        assertThat(loaded).isPresent();
    }

    @Test
    @DisplayName("remember 写入并 recall 读取")
    void shouldRememberAndRecall() {
        MemoryId epId = adapter.createEpisodicId("test-skill", "user-1");

        adapter.remember(epId, "第一件事", ctx);
        adapter.remember(epId, "第二件事", ctx);

        List<MemoryCell> recalled = adapter.recall(epId, 10);
        assertThat(recalled).hasSize(2);
        assertThat(recalled.get(0).content()).isEqualTo("第一件事");
        assertThat(recalled.get(1).content()).isEqualTo("第二件事");
    }

    @Test
    @DisplayName("rememberBatch 批量写入")
    void shouldRememberBatch() {
        MemoryId epId = adapter.createEpisodicId("batch-skill", "user-1");
        MemoryCell c1 = adapter.cellFrom("A", MemoryType.EPISODIC);
        MemoryCell c2 = adapter.cellFrom("B", MemoryType.EPISODIC);

        adapter.rememberBatch(epId, List.of(c1, c2), ctx);
        assertThat(adapter.recall(epId, 10)).hasSize(2);
    }

    @Test
    @DisplayName("createEpisodicId 生成规范 ID")
    void shouldCreateEpisodicId() {
        MemoryId id = adapter.createEpisodicId("summarizer", "user-1");
        assertThat(id.id()).startsWith("skill:summarizer:");
        assertThat(id.type()).isEqualTo(MemoryType.EPISODIC);
        assertThat(id.ownerKey()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("cellFrom 创建 MemoryCell")
    void shouldCreateCell() {
        MemoryCell cell = adapter.cellFrom("hello world", MemoryType.EPISODIC);
        assertThat(cell.content()).isEqualTo("hello world");
        assertThat(cell.memoryType()).isEqualTo(MemoryType.EPISODIC);
    }

    @Test
    @DisplayName("memoryRuntime 可获取内部 Runtime")
    void shouldExposeRuntime() {
        assertThat(adapter.memoryRuntime()).isNotNull();
    }
}
