package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Timeline} 的内存实现 — 基于 {@link CopyOnWriteArrayList}，测试和开发用。
 * <p>
 * 线程安全：append 操作通过 AtomicLong CAS 保证 globalSequence 唯一性，
 * CopyOnWriteArrayList 保证读无锁、写安全。
 */
public class DefaultTimeline implements Timeline {

    private final List<TimelineEntry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final String streamId;

    public DefaultTimeline(String streamId) {
        this.streamId = streamId;
    }

    @Override
    public TimelineEntry append(MemoryCell cell, String traceContext) {
        long seq = sequence.incrementAndGet();
        TimelinePosition pos = new TimelinePosition(seq, streamId);
        MemoryCell stamped = cell.withSequenceNumber(seq);
        TimelineEntry entry = TimelineEntry.of(seq, pos, stamped, traceContext);
        entries.add(entry);
        return entry;
    }

    @Override
    public List<TimelineEntry> appendBatch(List<MemoryCell> cells, String traceContext) {
        List<TimelineEntry> result = new ArrayList<>();
        for (MemoryCell cell : cells) {
            result.add(append(cell, traceContext));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<TimelineEntry> readFrom(TimelinePosition start, int limit) {
        List<TimelineEntry> result = new ArrayList<>();
        for (TimelineEntry entry : entries) {
            if (entry.position().offset() >= start.offset() && result.size() < limit) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<TimelineEntry> readRange(TimelinePosition start, TimelinePosition end) {
        List<TimelineEntry> result = new ArrayList<>();
        for (TimelineEntry entry : entries) {
            if (entry.position().offset() >= start.offset()
                    && entry.position().offset() <= end.offset()) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Optional<TimelineEntry> lastEntry() {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(entries.size() - 1));
    }

    @Override
    public TimelinePosition position() {
        return new TimelinePosition(sequence.get(), streamId);
    }

    @Override
    public long size() {
        return entries.size();
    }
}
