package com.yin.ai.platform.memory.lifecycle;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import com.yin.ai.platform.core.memory.store.WorkingMemoryStore;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryCascadeDeleteManager")
class MemoryCascadeDeleteManagerTest {

    private MemoryCascadeDeleteManager manager;
    private CountingEpisodicStore episodicStore;
    private CountingWorkingStore workingStore;

    @BeforeEach
    void setUp() {
        episodicStore = new CountingEpisodicStore();
        workingStore = new CountingWorkingStore();
        manager = new MemoryCascadeDeleteManager(episodicStore, workingStore);
    }

    @Test
    @DisplayName("deleteById 级联删除 episodic + working")
    void shouldCascadeDeleteById() {
        MemoryId id = MemoryId.of("mem-1", MemoryType.EPISODIC, "user-1");
        MemoryCascadeDeleteManager.CascadeDeleteResult result = manager.deleteById(id);

        assertThat(result.anyDeleted()).isTrue();
        assertThat(result.totalCellsDeleted()).isGreaterThan(0);
        assertThat(result.deletedStoreIds()).contains("episodic:mem-1", "working:mem-1");
    }

    @Test
    @DisplayName("deleteById 包含已删除的 store ID")
    void shouldTrackDeletedStoreIds() {
        MemoryId id = MemoryId.of("mem-2", MemoryType.EPISODIC, "user-2");
        MemoryCascadeDeleteManager.CascadeDeleteResult result = manager.deleteById(id);

        assertThat(result.deletedStoreIds()).hasSize(2);
    }

    @Test
    @DisplayName("episodic 故障不阻碍 working 删除")
    void shouldFailGracefully() {
        MemoryId id = MemoryId.of("fail-ep", MemoryType.EPISODIC, "user");
        manager = new MemoryCascadeDeleteManager(
                new FailingEpisodicStore(), workingStore);
        MemoryCascadeDeleteManager.CascadeDeleteResult result = manager.deleteById(id);

        assertThat(result.deletedStoreIds()).contains("working:fail-ep");
    }

    @Test
    @DisplayName("working 不存在时不报错")
    void shouldHandleMissingWorkingMemory() {
        MemoryId id = MemoryId.of("no-wm", MemoryType.EPISODIC, "user");
        manager = new MemoryCascadeDeleteManager(episodicStore,
                new WorkingMemoryStore() {
                    @Override public void save(WorkingMemory wm) {}
                    @Override public Optional<WorkingMemory> load(MemoryId id) { return Optional.empty(); }
                    @Override public void saveResumePoint(MemoryId id, ResumePoint point) {}
                    @Override public Optional<ResumePoint> loadResumePoint(MemoryId id, String label) { return Optional.empty(); }
                    @Override public List<ResumePoint> listResumePoints(MemoryId id) { return List.of(); }
                    @Override public void delete(MemoryId id) { throw new RuntimeException("not found"); }
                    @Override public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) { return false; }
                    @Override public StoreType storeType() { return StoreType.IN_MEMORY; }
                    @Override public boolean supports(MemoryType t) { return t == MemoryType.WORKING; }
                    @Override public StoreHealth health() { return StoreHealth.healthy(0); }
                });

        MemoryCascadeDeleteManager.CascadeDeleteResult result = manager.deleteById(id);
        assertThat(result.deletedStoreIds()).contains("episodic:no-wm");
    }

    // === Counting / Failing implementations ===

    static class CountingEpisodicStore implements EpisodicMemoryStore {
        final AtomicInteger deletes = new AtomicInteger();

        @Override public MemoryCell append(MemoryId id, MemoryCell cell) { return cell; }
        @Override public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells) { return cells; }
        @Override public List<MemoryCell> readTimeline(MemoryId id, int limit) { return List.of(); }
        @Override public List<MemoryCell> readAll(MemoryId id) { return List.of(); }
        @Override public long size(MemoryId id) { return 10; }
        @Override public void deleteAll(MemoryId id) { deletes.incrementAndGet(); }
        @Override public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) { return true; }
        @Override public StoreType storeType() { return StoreType.IN_MEMORY; }
        @Override public StoreHealth health() { return StoreHealth.healthy(0); }
    }

    static class CountingWorkingStore implements WorkingMemoryStore {
        final AtomicInteger deletes = new AtomicInteger();

        @Override public void save(WorkingMemory wm) {}
        @Override public Optional<WorkingMemory> load(MemoryId id) { return Optional.empty(); }
        @Override public void saveResumePoint(MemoryId id, ResumePoint point) {}
        @Override public Optional<ResumePoint> loadResumePoint(MemoryId id, String label) { return Optional.empty(); }
        @Override public List<ResumePoint> listResumePoints(MemoryId id) { return List.of(); }
        @Override public void delete(MemoryId id) { deletes.incrementAndGet(); }
        @Override public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) { return true; }
        @Override public StoreType storeType() { return StoreType.IN_MEMORY; }
        @Override public StoreHealth health() { return StoreHealth.healthy(0); }
    }

    static class FailingEpisodicStore implements EpisodicMemoryStore {
        @Override public MemoryCell append(MemoryId id, MemoryCell cell) { throw new RuntimeException("fail"); }
        @Override public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells) { throw new RuntimeException("fail"); }
        @Override public List<MemoryCell> readTimeline(MemoryId id, int limit) { throw new RuntimeException("fail"); }
        @Override public List<MemoryCell> readAll(MemoryId id) { throw new RuntimeException("fail"); }
        @Override public long size(MemoryId id) { throw new RuntimeException("fail"); }
        @Override public void deleteAll(MemoryId id) { throw new RuntimeException("fail"); }
        @Override public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) { return false; }
        @Override public StoreType storeType() { return StoreType.IN_MEMORY; }
        @Override public StoreHealth health() { return StoreHealth.healthy(0); }
    }
}
