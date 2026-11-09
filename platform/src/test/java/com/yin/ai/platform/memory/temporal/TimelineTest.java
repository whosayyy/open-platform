package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Timeline 追加日志")
class TimelineTest {

    private DefaultTimeline timeline;
    private MemoryCell cell1;
    private MemoryCell cell2;
    private MemoryCell cell3;

    @BeforeEach
    void setUp() {
        timeline = new DefaultTimeline("stream-1");
        cell1 = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "event-1");
        cell2 = MemoryCell.of(MemoryCellId.of("c2"), MemoryType.EPISODIC, "event-2");
        cell3 = MemoryCell.of(MemoryCellId.of("c3"), MemoryType.EPISODIC, "event-3");
    }

    @Test
    @DisplayName("追加后返回带位置的条目")
    void shouldReturnEntryWithPositionOnAppend() {
        TimelineEntry entry = timeline.append(cell1, "trace-1");

        assertNotNull(entry);
        assertEquals(1, entry.globalSequence());
        assertEquals("trace-1", entry.traceContext());
        assertEquals(cell1.cellId(), entry.cell().cellId());
        assertEquals("stream-1", entry.position().streamId());
    }

    @Test
    @DisplayName("多个追加后序列号递增")
    void shouldIncrementSequenceOnEachAppend() {
        TimelineEntry e1 = timeline.append(cell1, "t1");
        TimelineEntry e2 = timeline.append(cell2, "t2");
        TimelineEntry e3 = timeline.append(cell3, "t3");

        assertEquals(1, e1.globalSequence());
        assertEquals(2, e2.globalSequence());
        assertEquals(3, e3.globalSequence());
        assertEquals(3, timeline.size());
    }

    @Test
    @DisplayName("readFrom 返回从指定位置开始的条目")
    void shouldReadFromPosition() {
        timeline.append(cell1, "t1");
        timeline.append(cell2, "t2");
        timeline.append(cell3, "t3");

        List<TimelineEntry> result = timeline.readFrom(new TimelinePosition(2, "stream-1"), 10);

        assertEquals(2, result.size());
        assertEquals(2, result.get(0).globalSequence());
        assertEquals(3, result.get(1).globalSequence());
    }

    @Test
    @DisplayName("readFrom 遵守 limit 参数")
    void shouldRespectLimitOnReadFrom() {
        timeline.append(cell1, "t1");
        timeline.append(cell2, "t2");
        timeline.append(cell3, "t3");

        List<TimelineEntry> result = timeline.readFrom(TimelinePosition.origin("stream-1"), 2);

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("readRange 返回范围内的条目")
    void shouldReadRange() {
        timeline.append(cell1, "t1");
        timeline.append(cell2, "t2");
        timeline.append(cell3, "t3");

        List<TimelineEntry> result = timeline.readRange(
                new TimelinePosition(1, "stream-1"),
                new TimelinePosition(2, "stream-1"));

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("lastEntry 返回最后一条")
    void shouldReturnLastEntry() {
        assertTrue(timeline.lastEntry().isEmpty());

        timeline.append(cell1, "t1");
        timeline.append(cell2, "t2");

        assertTrue(timeline.lastEntry().isPresent());
        assertEquals(2, timeline.lastEntry().get().globalSequence());
    }

    @Test
    @DisplayName("position 反映当前偏移")
    void shouldReportCurrentPosition() {
        assertEquals(0, timeline.position().offset());

        timeline.append(cell1, "t1");
        assertEquals(1, timeline.position().offset());

        timeline.append(cell2, "t2");
        assertEquals(2, timeline.position().offset());
    }

    @Test
    @DisplayName("appendBatch 返回所有条目")
    void shouldAppendBatch() {
        List<MemoryCell> cells = List.of(cell1, cell2, cell3);
        List<TimelineEntry> entries = timeline.appendBatch(cells, "batch-trace");

        assertEquals(3, entries.size());
        assertEquals(3, timeline.size());
    }
}
