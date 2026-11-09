package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回放会话 — 从 Checkpoint 出发沿 Timeline 回放至目标状态。
 * <p>
 * 设计理由：
 * <ul>
 *   <li>Checkpoint 提供起点状态，Timeline 提供增量事件流</li>
 *   <li>逐条回放 TimelineEntry，模拟原始执行路径</li>
 *   <li>支持逐步调试（replayOne）和批量回放（replayTo）</li>
 *   <li>状态机：READY → REPLAYING → COMPLETED / FAILED，复用 CAS 模式</li>
 * </ul>
 */
public class ReplaySession {

    public enum State { READY, REPLAYING, COMPLETED, FAILED }

    private final MemoryId memoryId;
    private final Checkpoint checkpoint;
    private final Timeline timeline;
    private final Map<String, Object> recoveredState;
    private final List<TimelineEntry> replayedEntries;
    private final AtomicReference<State> state;

    public ReplaySession(MemoryId memoryId, Checkpoint checkpoint, Timeline timeline) {
        this.memoryId = memoryId;
        this.checkpoint = checkpoint;
        this.timeline = timeline;
        this.recoveredState = new HashMap<>();
        this.replayedEntries = new ArrayList<>();
        this.state = new AtomicReference<>(State.READY);
    }

    public MemoryId memoryId() { return memoryId; }
    public Checkpoint checkpoint() { return checkpoint; }
    public State state() { return state.get(); }
    public List<TimelineEntry> replayedEntries() { return List.copyOf(replayedEntries); }
    public Map<String, Object> recoveredState() { return Map.copyOf(recoveredState); }

    /** 单步回放一个条目。 */
    public void replayOne(TimelineEntry entry) {
        if (!state.compareAndSet(State.READY, State.REPLAYING)
                && !state.compareAndSet(State.REPLAYING, State.REPLAYING)) {
            return;
        }
        try {
            replayedEntries.add(entry);
            recoveredState.put("lastSequence", entry.globalSequence());
            recoveredState.put("lastPosition", entry.position());
            recoveredState.put("lastCellId", entry.cell().cellId().id());
        } catch (Exception e) {
            state.set(State.FAILED);
            recoveredState.put("error", e.getMessage());
        }
    }

    /** 从 Checkpoint 位置批量回放到指定位置。 */
    public void replayTo(TimelinePosition target) {
        if (state.get() == State.FAILED || state.get() == State.COMPLETED) {
            return;
        }
        state.set(State.REPLAYING);
        try {
            List<TimelineEntry> entries = timeline.readRange(
                    checkpoint.timelinePosition(), target);
            for (TimelineEntry entry : entries) {
                replayOne(entry);
                if (state.get() == State.FAILED) {
                    return;
                }
            }
            state.set(State.COMPLETED);
        } catch (Exception e) {
            state.set(State.FAILED);
            recoveredState.put("error", e.getMessage());
        }
    }

    /** 从 Checkpoint 位置回放到 Timeline 末尾。 */
    public void replayToEnd() {
        timeline.lastEntry().ifPresentOrElse(
                last -> replayTo(last.position()),
                () -> state.set(State.COMPLETED));
    }

    public boolean isComplete() {
        return state.get() == State.COMPLETED;
    }

    public boolean isFailed() {
        return state.get() == State.FAILED;
    }
}
